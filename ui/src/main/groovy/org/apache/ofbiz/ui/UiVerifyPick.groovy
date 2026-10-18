/*
 * UiVerifyPick.groovy — stateless verify-pick bridge for the React UI.
 * OFBiz verifies picks via an in-memory VerifyPickSession held in the web
 * session (VerifyPick screen). REST cannot hold that session, so this
 * service builds one, verifies all rows, and completes it in a single call.
 * complete() does the real work: issues order items to a new shipment,
 * updates reservations/picklist and marks the shipment PICKED.
 */
package org.apache.ofbiz.ui

import org.apache.ofbiz.shipment.verify.VerifyPickSession

Map uiVerifyPick() {
    String orderId        = parameters.orderId
    String shipGroupSeqId = parameters.shipGroupSeqId ?: '00001'
    List rows             = parameters.rows ?: []
    if (!rows) return error('No verify rows supplied')

    VerifyPickSession session = new VerifyPickSession(dispatcher, parameters.userLogin)
    if (parameters.facilityId) session.setFacilityId(parameters.facilityId)

    Locale loc = parameters.locale ?: Locale.getDefault()
    rows.each { r ->
        session.createRow(orderId, r.orderItemSeqId as String, shipGroupSeqId,
                r.productId as String, null,
                new BigDecimal(r.quantity.toString()), loc)
    }
    String shipmentId = session.complete(orderId, loc)
    Map result = success('Pick verified')
    result.shipmentId = shipmentId
    return result
}
