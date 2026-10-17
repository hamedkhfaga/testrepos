/*
 * UiPacking.groovy — stateless packing bridge for the React UI.
 * OFBiz packs via an in-memory PackingSession held in the web session
 * (PackOrder screen). REST cannot hold that session, so this service
 * builds one, adds all lines, and completes it in a single call.
 * complete() does the real work: shipment + packages + item issuances
 * (+ invoice via SECAs) and marks the shipment packed.
 */
package org.apache.ofbiz.ui

import org.apache.ofbiz.shipment.packing.PackingSession

Map uiPackOrder() {
    String orderId        = parameters.orderId
    String shipGroupSeqId = parameters.shipGroupSeqId ?: '00001'
    String facilityId     = parameters.facilityId
    List lines            = parameters.lines ?: []
    if (!lines) return error('No pack lines supplied')

    PackingSession session = new PackingSession(dispatcher, parameters.userLogin,
            facilityId, null, orderId, shipGroupSeqId)

    lines.each { l ->
        session.addOrIncreaseLine(orderId, l.orderItemSeqId as String,
                shipGroupSeqId, new BigDecimal(l.quantity.toString()),
                ((l.packageSeq ?: 1) as Integer).intValue())
    }
    (parameters.packageWeights ?: [:]).each { k, v ->
        if (v != null && v.toString()) {
            session.setPackageWeight((k as Integer).intValue(),
                    new BigDecimal(v.toString()))
        }
    }
    if (parameters.handlingInstructions) {
        session.setHandlingInstructions(parameters.handlingInstructions)
    }
    if (parameters.weightUomId) session.setWeightUomId(parameters.weightUomId)
    if (parameters.pickerPartyId) session.setPickerPartyId(parameters.pickerPartyId)

    String shipmentId = session.complete('true' == parameters.force)
    Map result = success('Order packed')
    result.shipmentId = shipmentId
    return result
}
