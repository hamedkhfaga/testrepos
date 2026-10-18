/*
 * ui plugin — digital bank-batch processing + generic audit logging.
 * (Moved from qrcore 2026-07-24; qrcore is QR-code only again.)
 * OFBiz core untouched: reads standard entities, writes only qrcore's
 * AuditEvent, and drives status changes through the STANDARD services
 * (setPaymentStatus / voidPayment) so every guard and rule still applies.
 */
package org.apache.ofbiz.ui

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue

/** Write one AuditEvent row. Used internally and via SECA. */
Map uiLogAudit() {
    GenericValue ev = delegator.makeValue('AuditEvent', [
        auditEventId: delegator.getNextSeqId('AuditEvent'),
        docType: parameters.docType,
        docId: parameters.docId,
        eventType: parameters.eventType,
        oldValue: parameters.oldValue,
        newValue: parameters.newValue,
        userLoginId: parameters.auditUserLoginId ?: userLogin?.userLoginId,
        eventDate: UtilDateTime.nowTimestamp(),
        remark: parameters.remark,
    ])
    ev.create()
    return success([auditEventId: ev.auditEventId])
}

/** SECA hook — logs every payment status transition (return stage of
 *  setPaymentStatus; oldStatusId comes from the service result). */
Map uiAuditPaymentStatus() {
    if (parameters.oldStatusId == parameters.statusId) return success()
    run service: 'uiLogAudit', with: [
        docType: 'PAYMENT', docId: parameters.paymentId,
        eventType: parameters.statusId == 'PMNT_VOID' ? 'VOID' : 'STATUS_CHANGE',
        oldValue: parameters.oldStatusId, newValue: parameters.statusId,
    ]
    return success()
}

/** SECA hook for voidPayment (carries no statusId parameter). */
Map uiAuditPaymentVoid() {
    run service: 'uiLogAudit', with: [
        docType: 'PAYMENT', docId: parameters.paymentId,
        eventType: 'VOID', newValue: 'PMNT_VOID',
    ]
    return success()
}

private static String xmlEsc(Object s) {
    return String.valueOf(s ?: '').replace('&', '&amp;').replace('<', '&lt;')
            .replace('>', '&gt;').replace('"', '&quot;')
}

/** EftAccount of a party — via its active PaymentMethod, newest first. */
private GenericValue eftOf(String partyId) {
    def pms = from('PaymentMethod')
            .where(partyId: partyId, paymentMethodTypeId: 'EFT_ACCOUNT')
            .filterByDate().orderBy('-fromDate').queryList()
    for (GenericValue pm : pms) {
        GenericValue eft = from('EftAccount')
                .where(paymentMethodId: pm.paymentMethodId).queryOne()
        if (eft) return eft
    }
    return null
}

/**
 * Generate an ISO 20022 bank file for a payment group.
 *  - outgoing members (DISBURSEMENT root) → pain.001 credit transfer
 *  - incoming members (RECEIPT root)      → pain.008 direct-debit collection
 * Output: fileName + fileContent (XML). Audits BANK_FILE_OUT on the group
 * and each member payment.
 */
Map uiGenerateBankFile() {
    String paymentGroupId = parameters.paymentGroupId
    GenericValue group = from('PaymentGroup').where(paymentGroupId: paymentGroupId).queryOne()
    if (!group) return error("Payment group ${paymentGroupId} not found")

    List<GenericValue> members = from('PaymentGroupMember')
            .where(paymentGroupId: paymentGroupId).filterByDate().queryList()
    if (!members) return error("Payment group ${paymentGroupId} has no active members")

    List payments = []
    members.each { m ->
        GenericValue p = from('Payment').where(paymentId: m.paymentId).queryOne()
        if (p) payments.add(p)
    }

    // direction from the first payment's type root
    String root = payments[0].paymentTypeId
    Set seen = []
    while (root && !seen.contains(root)) {
        seen.add(root)
        GenericValue t = from('PaymentType').where(paymentTypeId: root).cache().queryOne()
        if (!t || !t.parentTypeId) break
        root = t.parentTypeId
    }
    boolean outgoing = root != 'RECEIPT'

    String orgPartyId = outgoing ? payments[0].partyIdFrom : payments[0].partyIdTo
    GenericValue orgEft = eftOf(orgPartyId)
    GenericValue orgGroup = from('PartyGroup').where(partyId: orgPartyId).cache().queryOne()
    String orgName = orgGroup?.groupName ?: orgPartyId
    String now = UtilDateTime.nowTimestamp().toString().substring(0, 19).replace(' ', 'T')
    String msgId = "UI-${paymentGroupId}-${UtilDateTime.nowTimestamp().time}"
    BigDecimal total = 0
    payments.each { total += (it.amount ?: 0) }
    String ccy = payments[0].currencyUomId ?: 'USD'

    StringBuilder sb = new StringBuilder()
    StringBuilder txs = new StringBuilder()
    payments.each { GenericValue p ->
        String counter = outgoing ? p.partyIdTo : p.partyIdFrom
        GenericValue cEft = eftOf(counter)
        GenericValue cGrp = from('PartyGroup').where(partyId: counter).cache().queryOne()
        String cName = cGrp?.groupName
        if (!cName) {
            GenericValue per = from('Person').where(partyId: counter).cache().queryOne()
            cName = per ? "${per.firstName ?: ''} ${per.lastName ?: ''}".trim() : counter
        }
        txs.append(outgoing ? """
      <CdtTrfTxInf>
        <PmtId><EndToEndId>${xmlEsc(p.paymentId)}</EndToEndId></PmtId>
        <Amt><InstdAmt Ccy="${xmlEsc(p.currencyUomId ?: ccy)}">${p.amount}</InstdAmt></Amt>
        <CdtrAgt><FinInstnId><Nm>${xmlEsc(cEft?.bankName ?: 'UNKNOWN BANK')}</Nm>
          <Othr><Id>${xmlEsc(cEft?.routingNumber ?: '')}</Id></Othr></FinInstnId></CdtrAgt>
        <Cdtr><Nm>${xmlEsc(cName)}</Nm></Cdtr>
        <CdtrAcct><Id><Othr><Id>${xmlEsc(cEft?.accountNumber ?: '')}</Id></Othr></Id></CdtrAcct>
        <RmtInf><Ustrd>${xmlEsc(p.comments ?: ('Payment ' + p.paymentId))}</Ustrd></RmtInf>
      </CdtTrfTxInf>""" : """
      <DrctDbtTxInf>
        <PmtId><EndToEndId>${xmlEsc(p.paymentId)}</EndToEndId></PmtId>
        <InstdAmt Ccy="${xmlEsc(p.currencyUomId ?: ccy)}">${p.amount}</InstdAmt>
        <DbtrAgt><FinInstnId><Nm>${xmlEsc(cEft?.bankName ?: 'UNKNOWN BANK')}</Nm>
          <Othr><Id>${xmlEsc(cEft?.routingNumber ?: '')}</Id></Othr></FinInstnId></DbtrAgt>
        <Dbtr><Nm>${xmlEsc(cName)}</Nm></Dbtr>
        <DbtrAcct><Id><Othr><Id>${xmlEsc(cEft?.accountNumber ?: '')}</Id></Othr></Id></DbtrAcct>
        <RmtInf><Ustrd>${xmlEsc(p.comments ?: ('Collection ' + p.paymentId))}</Ustrd></RmtInf>
      </DrctDbtTxInf>""")
    }

    String doc = outgoing ? 'pain.001.001.09' : 'pain.008.001.08'
    String grpTag = outgoing ? 'CstmrCdtTrfInitn' : 'CstmrDrctDbtInitn'
    String pmtInf = outgoing ? """
    <PmtInf>
      <PmtInfId>${xmlEsc(paymentGroupId)}</PmtInfId>
      <PmtMtd>TRF</PmtMtd>
      <NbOfTxs>${payments.size()}</NbOfTxs>
      <CtrlSum>${total}</CtrlSum>
      <Dbtr><Nm>${xmlEsc(orgName)}</Nm></Dbtr>
      <DbtrAcct><Id><Othr><Id>${xmlEsc(orgEft?.accountNumber ?: '')}</Id></Othr></Id></DbtrAcct>
      <DbtrAgt><FinInstnId><Nm>${xmlEsc(orgEft?.bankName ?: '')}</Nm></FinInstnId></DbtrAgt>${txs}
    </PmtInf>""" : """
    <PmtInf>
      <PmtInfId>${xmlEsc(paymentGroupId)}</PmtInfId>
      <PmtMtd>DD</PmtMtd>
      <NbOfTxs>${payments.size()}</NbOfTxs>
      <CtrlSum>${total}</CtrlSum>
      <Cdtr><Nm>${xmlEsc(orgName)}</Nm></Cdtr>
      <CdtrAcct><Id><Othr><Id>${xmlEsc(orgEft?.accountNumber ?: '')}</Id></Othr></Id></CdtrAcct>
      <CdtrAgt><FinInstnId><Nm>${xmlEsc(orgEft?.bankName ?: '')}</Nm></FinInstnId></CdtrAgt>${txs}
    </PmtInf>"""

    sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<Document xmlns="urn:iso:std:iso:20022:tech:xsd:${doc}">
  <${grpTag}>
    <GrpHdr>
      <MsgId>${xmlEsc(msgId)}</MsgId>
      <CreDtTm>${now}</CreDtTm>
      <NbOfTxs>${payments.size()}</NbOfTxs>
      <CtrlSum>${total}</CtrlSum>
      <InitgPty><Nm>${xmlEsc(orgName)}</Nm></InitgPty>
    </GrpHdr>${pmtInf}
  </${grpTag}>
</Document>
""")

    run service: 'uiLogAudit', with: [docType: 'PAYMENT_GROUP', docId: paymentGroupId,
        eventType: 'BANK_FILE_OUT', newValue: msgId,
        remark: "${outgoing ? 'pain.001 credit transfer' : 'pain.008 direct debit'} · ${payments.size()} txs · total ${total} ${ccy}"]
    payments.each { p ->
        run service: 'uiLogAudit', with: [docType: 'PAYMENT', docId: p.paymentId,
            eventType: 'BANK_FILE_OUT', newValue: msgId,
            remark: "included in bank file for group ${paymentGroupId}"]
    }

    return success([fileName: "${doc}-${paymentGroupId}.xml",
                    fileContent: sb.toString(), msgId: msgId])
}

/**
 * Process a bank status-response file (pain.002 style): per transaction
 * EndToEndId (= paymentId) with status ACSC (settled) → confirm, or
 * RJCT (+ reason) → void. All through STANDARD services; every outcome
 * audited. Output: results list [{paymentId, txStatus, action, message}].
 */
Map uiProcessBankResponse() {
    String paymentGroupId = parameters.paymentGroupId
    String content = parameters.fileContent
    if (!content) return error('fileContent is required')

    def xml
    try { xml = new XmlSlurper().parseText(content) }
    catch (Exception e) { return error("Cannot parse response XML: ${e.message}") }

    // ── duplicate / tamper guard ─────────────────────────────────────────
    // Banks may redeliver a file (same MsgId, same content) — that is safe,
    // the per-transaction idempotency handles it. A MODIFIED duplicate
    // (same MsgId, different content) is a red flag and is REJECTED.
    String respMsgId = xml.depthFirst().find { it.name() == 'MsgId' }?.text()
    String contentHash = java.security.MessageDigest.getInstance('SHA-256')
            .digest(content.getBytes('UTF-8')).encodeHex().toString()
    if (respMsgId) {
        def prior = from('AuditEvent')
                .where(docType: 'PAYMENT_GROUP', docId: paymentGroupId,
                       eventType: 'BANK_RESPONSE', newValue: respMsgId)
                .queryList()
        def tampered = prior.find { it.oldValue && it.oldValue != contentHash }
        if (tampered) {
            run service: 'uiLogAudit', with: [docType: 'PAYMENT_GROUP',
                docId: paymentGroupId, eventType: 'BANK_RESP_REJECTED',
                newValue: respMsgId, oldValue: contentHash,
                remark: 'MODIFIED duplicate: same MsgId as a previously processed ' +
                        'response but different content — rejected, verify with the bank']
            return error("Response MsgId ${respMsgId} was already processed with " +
                    'DIFFERENT content. Modified duplicates are rejected — verify ' +
                    'the file with your bank. Nothing was changed.')
        }
    }

    List results = []
    def txNodes = xml.depthFirst().findAll { it.name() == 'TxInfAndSts' }
    if (!txNodes) return error('No TxInfAndSts entries found in the response file')

    // ── group-binding guard ──────────────────────────────────────────────
    // A response file must belong to the group it is uploaded against:
    //  1. its OrgnlMsgId must match a bank file WE generated for THIS group
    //     (BANK_FILE_OUT audit trail) — if it belongs to another group, say so;
    //  2. every transaction must reference a member payment of this group —
    //     non-members are never touched;
    //  3. if NO transaction belongs to the group, refuse outright.
    Set memberIds = from('PaymentGroupMember').where(paymentGroupId: paymentGroupId)
            .queryList().collect { it.paymentId } as Set
    String origMsgId = xml.depthFirst().find { it.name() == 'OrgnlMsgId' }?.text()
    String origMsgNote = ''
    if (origMsgId) {
        def fileOut = from('AuditEvent')
                .where(docType: 'PAYMENT_GROUP', docId: paymentGroupId,
                       eventType: 'BANK_FILE_OUT', newValue: origMsgId)
                .queryList()
        if (!fileOut) {
            def owner = from('AuditEvent')
                    .where(docType: 'PAYMENT_GROUP', eventType: 'BANK_FILE_OUT',
                           newValue: origMsgId)
                    .queryFirst()
            if (owner) {
                // the reference provably belongs to ANOTHER group — hard stop
                return error("This response answers original message ${origMsgId}, " +
                        "which was not generated for group ${paymentGroupId} — it " +
                        "belongs to group ${owner.docId}. Upload it on that group.")
            }
            // reference matches nothing we ever generated (legacy sample or an
            // imperfect bank reference): proceed — the per-transaction membership
            // guard below still protects the group — but note the anomaly
            origMsgNote = " (OrgnlMsgId ${origMsgId} matches no generated file — " +
                    'membership-validated instead)'
        }
    }
    boolean anyMember = txNodes.any { tx ->
        memberIds.contains(tx.'OrgnlEndToEndId'.text() ?: tx.'OrgnlTxRef'.text())
    }
    if (!anyMember) {
        return error("None of the transactions in this file reference a payment of " +
                "group ${paymentGroupId}. Wrong file or wrong group — nothing was changed.")
    }

    txNodes.each { tx ->
        String paymentId = tx.'OrgnlEndToEndId'.text() ?: tx.'OrgnlTxRef'.text()
        String txSts = tx.'TxSts'.text()
        String reason = tx.'StsRsnInf'.'AddtlInf'.text()
                ?: tx.'StsRsnInf'.'Rsn'.'Cd'.text()
        GenericValue p = from('Payment').where(paymentId: paymentId).queryOne()
        if (!p) {
            results.add([paymentId: paymentId, txStatus: txSts,
                         action: 'SKIPPED', message: 'payment not found'])
            return
        }
        if (!memberIds.contains(paymentId)) {
            results.add([paymentId: paymentId, txStatus: txSts,
                         action: 'SKIPPED',
                         message: "not a member of group ${paymentGroupId} — not touched"])
            return
        }
        try {
            // IDEMPOTENT / RESUMABLE by design: a transaction already in its
            // target state is skipped (no status change, no duplicate audit),
            // reported as ALREADY_*. Re-uploading the same file after an
            // interruption only acts on the still-unprocessed items.
            if (txSts == 'ACSC') {
                if (p.statusId == 'PMNT_CONFIRMED') {
                    results.add([paymentId: paymentId, txStatus: txSts,
                                 action: 'ALREADY_CONFIRMED',
                                 message: 'previously processed — skipped'])
                    return
                }
                run service: 'setPaymentStatus', with: [paymentId: paymentId,
                                                       statusId: 'PMNT_CONFIRMED']
                run service: 'uiLogAudit', with: [docType: 'PAYMENT', docId: paymentId,
                    eventType: 'BANK_ACK', oldValue: p.statusId, newValue: 'PMNT_CONFIRMED',
                    remark: "bank settled (ACSC), group ${paymentGroupId}"]
                results.add([paymentId: paymentId, txStatus: txSts,
                             action: 'CONFIRMED', message: 'settled by bank'])
            } else if (txSts == 'RJCT') {
                if (['PMNT_VOID', 'PMNT_CANCELLED'].contains(p.statusId)) {
                    results.add([paymentId: paymentId, txStatus: txSts,
                                 action: 'ALREADY_VOID',
                                 message: 'previously processed — skipped'])
                    return
                }
                run service: 'voidPayment', with: [paymentId: paymentId]
                run service: 'uiLogAudit', with: [docType: 'PAYMENT', docId: paymentId,
                    eventType: 'BANK_REJECT', oldValue: p.statusId, newValue: 'PMNT_VOID',
                    remark: "bank rejected (${reason ?: 'no reason given'}), group ${paymentGroupId}"]
                results.add([paymentId: paymentId, txStatus: txSts,
                             action: 'VOIDED', message: reason ?: 'rejected by bank'])
            } else {
                results.add([paymentId: paymentId, txStatus: txSts,
                             action: 'NONE', message: "unhandled status ${txSts}"])
            }
        } catch (Exception e) {
            results.add([paymentId: paymentId, txStatus: txSts,
                         action: 'ERROR', message: e.message])
        }
    }

    // record MsgId + content hash — the reference for the tamper guard above
    run service: 'uiLogAudit', with: [docType: 'PAYMENT_GROUP', docId: paymentGroupId,
        eventType: 'BANK_RESPONSE', newValue: respMsgId, oldValue: contentHash,
        remark: "processed ${results.size()} transactions" +
                (respMsgId ? " (MsgId ${respMsgId})" : ' (no MsgId in file!)') +
                origMsgNote]

    return success([results: results])
}
