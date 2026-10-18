/*
 * UiNotes.groovy — shipment note bridge for the React UI.
 * OFBiz has no REST-exported shipment-note service; this creates the
 * NoteData + ShipmentNote pair exactly like the facility screen does.
 */
package org.apache.ofbiz.ui

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue

Map uiCreateShipmentNote() {
    String shipmentId = parameters.shipmentId
    String noteText   = parameters.note
    GenericValue userLogin = parameters.userLogin

    String noteId = delegator.getNextSeqId('NoteData')
    GenericValue note = delegator.makeValue('NoteData', [
        noteId:       noteId,
        noteInfo:     noteText,
        noteParty:    userLogin?.getString('partyId'),
        noteDateTime: UtilDateTime.nowTimestamp(),
    ])
    note.create()

    GenericValue link = delegator.makeValue('ShipmentNote', [
        shipmentId: shipmentId, noteId: noteId,
    ])
    link.create()

    Map result = success('Shipment note created')
    result.noteId = noteId
    return result
}
