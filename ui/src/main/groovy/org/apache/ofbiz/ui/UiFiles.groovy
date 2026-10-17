/*
 * ui plugin — server-side bank file exchange directories.
 *
 * Default location is RELATIVE to the OFBiz home (survives plugin upgrades,
 * data/code separation): runtime/uploads/bankfiles/{out,in,archive}.
 * Overridable via SystemProperty (resource "ui"):
 *   bankfile.base.dir            — whole base (absolute, or relative to home)
 *   bankfile.out.dir / bankfile.in.dir / bankfile.archive.dir — per folder
 * Resolution: specific property → base property → default.
 * Safety: file names are plain names (no path parts), resolved paths must
 * stay inside the configured folder, outbound never overwrites (numbered
 * suffix), inbound is archived (moved + timestamped), never deleted.
 */
package org.apache.ofbiz.ui

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue

private String uiProp(String name) {
    GenericValue p = from('SystemProperty')
            .where(systemResourceId: 'ui', systemPropertyId: name).queryOne()
    String v = p?.systemPropertyValue
    return v ? v.trim() : null
}

private File uiResolveDir(String kind) {
    String home = System.getProperty('ofbiz.home') ?: '.'
    String specific = uiProp("bankfile.${kind}.dir")
    File dir
    if (specific) {
        File f = new File(specific)
        dir = f.isAbsolute() ? f : new File(home, specific)
    } else {
        String base = uiProp('bankfile.base.dir') ?: 'runtime/uploads/bankfiles'
        File b = new File(base)
        File baseDir = b.isAbsolute() ? b : new File(home, base)
        dir = new File(baseDir, kind)
    }
    dir.mkdirs()
    return dir.getCanonicalFile()
}

/** A file inside dir, by plain name only — rejects any path traversal. */
private File uiSafeFile(File dir, String fileName) {
    if (!fileName || fileName.contains('/') || fileName.contains('\\')
            || fileName.contains('..')) {
        throw new IllegalArgumentException("Illegal file name: ${fileName}")
    }
    File f = new File(dir, fileName).getCanonicalFile()
    if (!f.getPath().startsWith(dir.getPath())) {
        throw new IllegalArgumentException("Path escapes the configured folder")
    }
    return f
}

/** Effective configuration — what the operator sees on the Folders card. */
Map uiGetBankFileConfig() {
    Map out = [:]
    ['out', 'in', 'archive'].each { kind ->
        out[kind + 'Dir'] = uiResolveDir(kind).getPath()
        out[kind + 'Source'] = uiProp("bankfile.${kind}.dir") ? 'property'
                : (uiProp('bankfile.base.dir') ? 'base property' : 'default')
    }
    out.baseProperty = uiProp('bankfile.base.dir') ?: ''
    out.outProperty = uiProp('bankfile.out.dir') ?: ''
    out.inProperty = uiProp('bankfile.in.dir') ?: ''
    out.archiveProperty = uiProp('bankfile.archive.dir') ?: ''
    return success([config: out])
}

/** Generate the group's bank file AND write it into the outbound folder. */
Map uiDeliverBankFile() {
    Map gen = run service: 'uiGenerateBankFile',
            with: [paymentGroupId: parameters.paymentGroupId]
    String fileName = gen.fileName
    String content = gen.fileContent
    if (!content) return error('Bank file generation returned no content')
    File dir = uiResolveDir('out')
    File f = uiSafeFile(dir, fileName)
    int n = 1
    while (f.exists()) { // never overwrite — numbered suffix
        String alt = fileName.replaceFirst(/(\.[^.]+)?$/) { m -> "-${n}${m[0] ?: ''}" }
        f = uiSafeFile(dir, alt); n++
    }
    f.setText(content, 'UTF-8')
    run service: 'uiLogAudit', with: [docType: 'PAYMENT_GROUP',
        docId: parameters.paymentGroupId, eventType: 'BANK_FILE_DELIVERED',
        newValue: gen.msgId, remark: "written to ${f.getPath()}"]
    return success([filePath: f.getPath(), fileName: f.getName(), msgId: gen.msgId])
}

/** List files waiting in the inbound folder, newest first, with a kind sniff. */
Map uiScanBankInbox() {
    File dir = uiResolveDir('in')
    List files = []
    (dir.listFiles() ?: new File[0]).findAll { it.isFile() }
            .sort { -it.lastModified() }.each { File f ->
        String head = ''
        try { head = f.newInputStream().withCloseable {
            new String(it.readNBytes(4000), 'UTF-8') } } catch (Exception ignored) {}
        String kind = 'other'
        if (head.contains('CstmrPmtStsRpt') || head.contains('TxInfAndSts')) kind = 'response'
        else if (head.contains('BkToCstmrStmt') || head.contains('<Ntry>')) kind = 'statement'
        else if (f.getName().toLowerCase().endsWith('.csv')) kind = 'statement'
        files.add([fileName: f.getName(), size: f.length(), kind: kind,
                   modifiedDate: UtilDateTime.getTimestamp(f.lastModified()).toString()])
    }
    return success([files: files, inDir: dir.getPath()])
}

/** Return the content of one inbox file (validated plain name). */
Map uiReadInboxFile() {
    File f = uiSafeFile(uiResolveDir('in'), parameters.fileName)
    if (!f.exists()) return error("File not found in inbox: ${parameters.fileName}")
    return success([fileContent: f.getText('UTF-8'), fileName: f.getName()])
}

/** Move a processed inbox file to the archive folder (timestamped, audited). */
Map uiArchiveInboxFile() {
    File src = uiSafeFile(uiResolveDir('in'), parameters.fileName)
    if (!src.exists()) return error("File not found in inbox: ${parameters.fileName}")
    File archDir = uiResolveDir('archive')
    String stamp = UtilDateTime.nowTimestamp().toString()
            .substring(0, 19).replaceAll(/[ :]/, '-')
    File dst = uiSafeFile(archDir, "${stamp}_${src.getName()}")
    if (!src.renameTo(dst)) { // cross-volume fallback: copy + delete source
        dst.setText(src.getText('UTF-8'), 'UTF-8')
        src.delete()
    }
    run service: 'uiLogAudit', with: [docType: 'BANK_FILE', docId: src.getName(),
        eventType: 'BANK_FILE_ARCHIVED', newValue: dst.getPath(),
        remark: parameters.remark ?: 'processed and archived']
    return success([archivedPath: dst.getPath()])
}
