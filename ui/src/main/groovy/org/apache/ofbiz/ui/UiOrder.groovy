/*
 * ui plugin — Order entry bridges (relocated from asseterp 2026-08-22 by
 * user decision: core-migration capabilities must not depend on the
 * optional Asset ERP module). Identical logic; wraps the STANDARD
 * storeOrder — the wrapper exists only because the standard entry point
 * (ShoppingCart) is browser-session-bound and storeOrder needs
 * GenericValue inputs JSON cannot supply.
 *
 * uiCalculateOrderTotals  — price + tax + promo preview (no DB write)
 * uiCreateOrder           — full order creation (calls storeOrder)
 *
 * Both accept plain Map/String from REST JSON (LinkedHashMap).
 * GenericValue construction happens here so storeOrder receives correct types.
 */

import org.apache.ofbiz.entity.GenericValue
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp

// ─────────────────────────────────────────────────────────────────────────────
// CALCULATE ORDER TOTALS  (preview — no DB write)
// Returns: subtotal, discountTotal, taxTotal, shippingTotal, grandTotal,
//          computedItems[], taxAdjustments[], discountAdjustments[]
// ─────────────────────────────────────────────────────────────────────────────
Map uiCalculateOrderTotals() {
    List   rawItems       = parameters.orderItems     ?: []
    String partyId        = parameters.partyId
    String productStoreId = parameters.productStoreId
    String currencyUom    = parameters.currencyUom    ?: 'USD'
    String agreementId    = parameters.agreementId
    String orderTypeId    = parameters.orderTypeId    ?: 'SALES_ORDER'
    Set    promoCodes     = parameters.promoCodes     ? parameters.promoCodes as Set : [] as Set
    BigDecimal shippingAmt = parameters.shippingAmount != null
                             ? new BigDecimal(parameters.shippingAmount.toString()) : BigDecimal.ZERO

    if (!rawItems) return error('orderItems required')

    // ── Step 1: resolve prices for each item ─────────────────────────────────
    List computedItems      = []
    List<GenericValue> productGVs = []
    List<BigDecimal> itemAmounts  = []
    List<BigDecimal> itemPrices   = []
    List<BigDecimal> itemQtys     = []
    BigDecimal subtotal           = BigDecimal.ZERO
    BigDecimal discountTotal      = BigDecimal.ZERO
    List discountAdjustments      = []

    rawItems.eachWithIndex { item, idx ->
        GenericValue product = delegator.findOne('Product', [productId: item.productId], false)
        BigDecimal qty = toBD(item.quantity) ?: BigDecimal.ONE

        BigDecimal unitPrice
        BigDecimal listPrice
        List priceInfos = []

        if (product) {
            // Use OFBiz price engine: agreement prices, price rules, promo prices
            String purpose = (orderTypeId == 'PURCHASE_ORDER') ? 'PURCHASE' : 'PURCHASE'
            Map priceCtx = [
                userLogin:               parameters.userLogin,
                product:                 product,
                partyId:                 partyId,
                productStoreId:          productStoreId,
                agreementId:             agreementId,
                quantity:                qty,
                currencyUomId:           currencyUom,
                productPricePurposeId:   purpose,
                checkIncludeVat:         'N',
            ]
            Map pr = dispatcher.runSync('calculateProductPrice', priceCtx)
            unitPrice  = (item.unitPrice != null) ? toBD(item.unitPrice) : (pr.price ?: BigDecimal.ZERO)
            listPrice  = pr.listPrice ?: unitPrice
            priceInfos = pr.orderItemPriceInfos ?: []

            // Discount = listPrice - unitPrice (per unit × qty).
            // ONLY when the caller did NOT pass an explicit price on a sales
            // order: an explicit unitPrice is the agreed price (OFBiz
            // isModifiedPrice semantics) — synthesizing a discount on top of
            // it double-counts on the invoice. Purchase orders never get one:
            // supplier cost vs retail list price is not a discount.
            if (item.unitPrice == null && orderTypeId == 'SALES_ORDER' && listPrice > unitPrice) {
                BigDecimal disc = listPrice.subtract(unitPrice).multiply(qty).setScale(2, RoundingMode.HALF_UP)
                discountTotal  = discountTotal.add(disc)
                discountAdjustments << [
                    orderAdjustmentTypeId: 'DISCOUNT_ADJUSTMENT',
                    amount:                disc.negate(),
                    description:           "Price discount on ${item.productId}",
                    productId:             item.productId,
                ]
            }
            productGVs << product
        } else {
            unitPrice = toBD(item.unitPrice) ?: BigDecimal.ZERO
            listPrice = unitPrice
            productGVs << delegator.makeValue('Product', [productId: item.productId])
        }

        BigDecimal lineTotal = unitPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP)
        subtotal = subtotal.add(lineTotal)

        itemAmounts << lineTotal
        itemPrices  << unitPrice
        itemQtys    << qty

        computedItems << [
            orderItemSeqId:  item.orderItemSeqId  ?: String.format('%05d', idx + 1),
            productId:        item.productId,
            quantity:         qty,
            unitPrice:        unitPrice,
            listPrice:        listPrice,
            lineTotal:        lineTotal,
            itemDescription:  item.itemDescription ?: item.productName ?: item.productId,
            comments:         item.comments ?: '',
            priceInfos:       priceInfos,
        ]
    }

    // ── Step 2: tax calculation ───────────────────────────────────────────────
    List taxAdjustments = []
    BigDecimal taxTotal = BigDecimal.ZERO

    // sales tax is a sell-side concept: never compute it for purchase orders,
    // even if a caller wrongly passes a productStoreId
    if (productStoreId && productGVs && orderTypeId != 'PURCHASE_ORDER') {
        try {
            // Resolve the party's shipping address — calcTax needs it to find the
            // tax jurisdiction (state/country). Fall back: SHIPPING → GENERAL → BILLING.
            GenericValue shipAddr = null
            if (partyId) {
                for (String purpose in ['SHIPPING_LOCATION', 'GENERAL_LOCATION', 'BILLING_LOCATION']) {
                    def pcmp = delegator.findByAnd('PartyContactMechPurpose',
                            [partyId: partyId, contactMechPurposeTypeId: purpose], null, false)
                            .find { it.getTimestamp('thruDate') == null }
                    if (pcmp) {
                        shipAddr = delegator.findOne('PostalAddress',
                                [contactMechId: pcmp.getString('contactMechId')], false)
                        if (shipAddr) break
                    }
                }
            }

            Map taxCtx = [
                userLogin:            parameters.userLogin,
                productStoreId:       productStoreId,
                billToPartyId:        partyId,
                itemProductList:      productGVs,
                itemAmountList:       itemAmounts,
                itemPriceList:        itemPrices,
                itemQuantityList:     itemQtys,
                itemShippingList:     itemQtys.collect { BigDecimal.ZERO },
                orderShippingAmount:  shippingAmt,
                orderPromotionsAmount: discountTotal.negate(),
            ]
            if (shipAddr) taxCtx.shippingAddress = shipAddr
            // run in a NEW transaction — if calcTax fails (e.g. no tax-jurisdiction
            // address) it must not poison the parent transaction
            Map taxResult = dispatcher.runSync('calcTax', taxCtx, 60, true)
            if (taxResult._ERROR_MESSAGE_) throw new Exception(taxResult._ERROR_MESSAGE_)
            // calcTax returns: orderAdjustments (order-level, e.g. on shipping)
            // + itemAdjustments (List of Lists — per-item tax adjustments)
            List allTaxAdjs = []
            allTaxAdjs.addAll(taxResult.orderAdjustments ?: [])
            (taxResult.itemAdjustments ?: []).each { itemAdjList ->
                if (itemAdjList) allTaxAdjs.addAll(itemAdjList)
            }
            allTaxAdjs.each { adj ->
                BigDecimal amt = adj.getBigDecimal('amount') ?: BigDecimal.ZERO
                taxTotal = taxTotal.add(amt)
                taxAdjustments << [
                    orderAdjustmentTypeId: adj.get('orderAdjustmentTypeId') ?: 'SALES_TAX',
                    amount:                amt,
                    description:           adj.get('description') ?: 'Tax',
                    taxAuthGeoId:          adj.get('taxAuthGeoId'),
                    taxAuthPartyId:        adj.get('taxAuthPartyId'),
                    primaryGeoId:          adj.get('primaryGeoId'),
                    secondaryGeoId:        adj.get('secondaryGeoId'),
                    taxAuthorityRateSeqId: adj.get('taxAuthorityRateSeqId'),
                    sourcePercentage:      adj.get('sourcePercentage'),
                ]
            }
        } catch (e) {
            // Tax authority not configured — non-fatal, zero tax
        }
    }

    BigDecimal grandTotal = subtotal
                              .subtract(discountTotal)
                              .add(taxTotal)
                              .add(shippingAmt)
                              .setScale(2, RoundingMode.HALF_UP)

    return success([
        computedItems:        computedItems,
        subtotal:             subtotal.setScale(2, RoundingMode.HALF_UP),
        discountTotal:        discountTotal.setScale(2, RoundingMode.HALF_UP),
        taxTotal:             taxTotal.setScale(2, RoundingMode.HALF_UP),
        shippingTotal:        shippingAmt.setScale(2, RoundingMode.HALF_UP),
        grandTotal:           grandTotal,
        taxAdjustments:       taxAdjustments,
        discountAdjustments:  discountAdjustments,
    ])
}

// ─────────────────────────────────────────────────────────────────────────────
// CREATE ORDER  (calls uiCalculateOrderTotals then storeOrder)
// ─────────────────────────────────────────────────────────────────────────────
Map uiCreateOrder() {
    List rawItems = parameters.orderItems ?: []
    if (!rawItems) return error('orderItems is required and must contain at least one item')

    // ── Step 1: compute prices + tax + discounts ──────────────────────────────
    Map calcCtx = [
        userLogin:      parameters.userLogin,
        orderItems:     rawItems,
        partyId:        parameters.partyId,
        productStoreId: parameters.productStoreId,
        currencyUom:    parameters.currencyUom ?: 'USD',
        agreementId:    parameters.agreementId,
        orderTypeId:    parameters.orderTypeId ?: 'SALES_ORDER',
        promoCodes:     parameters.promoCodes,
        shippingAmount: parameters.shippingAmount,
    ]
    Map calc = dispatcher.runSync('uiCalculateOrderTotals', calcCtx)
    if (calc._ERROR_MESSAGE_) return error(calc._ERROR_MESSAGE_)

    List computedItems       = calc.computedItems       ?: []
    List taxAdjustments      = calc.taxAdjustments      ?: []
    List discountAdjustments = calc.discountAdjustments ?: []
    BigDecimal grandTotal    = calc.grandTotal          ?: BigDecimal.ZERO
    BigDecimal shippingAmt   = calc.shippingTotal       ?: BigDecimal.ZERO

    // ── Step 2: build GenericValue orderItems ────────────────────────────────
    List<GenericValue> orderItems = []
    computedItems.eachWithIndex { item, idx ->
        GenericValue oi = delegator.makeValue('OrderItem')
        oi.set('orderItemSeqId',  item.orderItemSeqId ?: String.format('%05d', idx + 1))
        // item type from UI selector (PRODUCT / RENTAL / WORK / BULK / INVENTORY / SUPPLIES / ASSET)
        oi.set('orderItemTypeId', rawItems[idx]?.orderItemTypeId ?: 'PRODUCT_ORDER_ITEM')
        oi.set('productId',       item.productId)
        oi.set('quantity',        item.quantity)
        oi.set('unitPrice',       item.unitPrice)
        oi.set('unitListPrice',   item.listPrice)
        oi.set('itemDescription', item.itemDescription)
        oi.set('comments',        item.comments ?: '')
        oi.set('statusId',        'ITEM_CREATED')
        // explicit caller price = agreed/modified price (matches OFBiz cart)
        oi.set('isModifiedPrice', rawItems[idx]?.unitPrice != null ? 'Y' : 'N')
        // Dates + quote linkage from original raw item
        def raw = rawItems[idx]
        if (raw?.quoteId) {
            oi.set('quoteId', raw.quoteId)
            if (raw.quoteItemSeqId) oi.set('quoteItemSeqId', raw.quoteItemSeqId)
        }
        if (raw?.shipAfterDate)  oi.set('shipAfterDate',  toTimestamp(raw.shipAfterDate))
        if (raw?.shipBeforeDate) oi.set('shipBeforeDate', toTimestamp(raw.shipBeforeDate))
        orderItems << oi
    }

    // ── Step 3: build GenericValue orderAdjustments ──────────────────────────
    // Merge: caller-supplied + computed discounts + computed tax
    List allAdjMaps = []
    allAdjMaps.addAll(parameters.orderAdjustments ?: [])    // manual (coupons etc)
    allAdjMaps.addAll(discountAdjustments)                   // price-rule discounts
    allAdjMaps.addAll(taxAdjustments)                        // tax authority

    // Shipping adjustment
    if (shippingAmt > BigDecimal.ZERO) {
        allAdjMaps << [
            orderAdjustmentTypeId: 'SHIPPING_CHARGES',
            amount:                shippingAmt,
            description:           'Shipping',
        ]
    }

    List<GenericValue> orderAdjustments = allAdjMaps.collect { adj ->
        GenericValue oa = delegator.makeValue('OrderAdjustment')
        oa.set('orderAdjustmentTypeId', adj.orderAdjustmentTypeId ?: 'MISC_CHARGE')
        oa.set('amount',      adj.amount != null ? toBD(adj.amount) : BigDecimal.ZERO)
        oa.set('description', adj.description ?: '')
        if (adj.taxAuthGeoId)          oa.set('taxAuthGeoId',          adj.taxAuthGeoId)
        if (adj.taxAuthPartyId)        oa.set('taxAuthPartyId',        adj.taxAuthPartyId)
        if (adj.primaryGeoId)          oa.set('primaryGeoId',          adj.primaryGeoId)
        if (adj.secondaryGeoId)        oa.set('secondaryGeoId',        adj.secondaryGeoId)
        if (adj.taxAuthorityRateSeqId) oa.set('taxAuthorityRateSeqId', adj.taxAuthorityRateSeqId)
        if (adj.sourcePercentage)      oa.set('sourcePercentage',      toBD(adj.sourcePercentage))
        oa
    }

    // ── Step 4: payment preferences ──────────────────────────────────────────
    List rawPay = parameters.orderPaymentInfo ?: []
    List<GenericValue> orderPaymentInfo = rawPay.collect { pay ->
        GenericValue op = delegator.makeValue('OrderPaymentPreference')
        op.set('paymentMethodTypeId', pay.paymentMethodTypeId ?: 'EXT_OFFLINE')
        op.set('statusId',            'PAYMENT_NOT_RECEIVED')
        // stored payment method (credit card / EFT account) of the party
        if (pay.paymentMethodId)  op.set('paymentMethodId', pay.paymentMethodId)
        if (pay.billingAccountId) op.set('billingAccountId', pay.billingAccountId)
        if (pay.maxAmount)        op.set('maxAmount', toBD(pay.maxAmount))
        op
    }
    if (!orderPaymentInfo) {
        GenericValue op = delegator.makeValue('OrderPaymentPreference')
        op.set('paymentMethodTypeId', 'EXT_OFFLINE')
        op.set('statusId', 'PAYMENT_NOT_RECEIVED')
        orderPaymentInfo << op
    }

    // ── Step 4b: order terms (OFBiz Order Terms screen parity) ──────────────
    List<GenericValue> orderTermsList = (parameters.orderTerms ?: []).collect { t ->
        GenericValue ot = delegator.makeValue('OrderTerm')
        ot.set('termTypeId',     t.termTypeId)
        ot.set('orderItemSeqId', t.orderItemSeqId ?: '_NA_')
        if (t.termValue != null) ot.set('termValue', toBD(t.termValue))
        if (t.termDays  != null) ot.set('termDays',  t.termDays as Long)
        if (t.textValue)         ot.set('textValue', t.textValue)
        if (t.description)       ot.set('description', t.description)
        ot
    }

    // ── Step 5: notes ────────────────────────────────────────────────────────
    List<Map> orderNotes = (parameters.orderNotes ?: []).collect { n ->
        [noteInfo: n.noteInfo ?: n.toString(), internalNote: n.internalNote ?: 'N']
    }

    // ── Step 6: promo codes (Set<String>) ────────────────────────────────────
    Set<String> promoCodes = parameters.promoCodes ? parameters.promoCodes as Set : null

    // ── Step 7: call storeOrder ───────────────────────────────────────────────
    Map storeCtx = [
        userLogin:        parameters.userLogin,
        partyId:          parameters.partyId,
        orderTypeId:      parameters.orderTypeId ?: 'SALES_ORDER',
        currencyUom:      parameters.currencyUom ?: 'USD',
        orderItems:       orderItems,
        orderAdjustments: orderAdjustments,
        orderPaymentInfo: orderPaymentInfo,
        grandTotal:       grandTotal,
    ]
    if (orderTermsList) storeCtx.orderTerms = orderTermsList

    ['productStoreId','salesChannelEnumId','orderName','externalId','webSiteId',
     'agreementId','workEffortId','billingAccountId','originFacilityId',
     'placingCustomerPartyId','billToCustomerPartyId','shipToCustomerPartyId',
     'endUserCustomerPartyId','billFromVendorPartyId','shipFromVendorPartyId',
     'supplierAgentPartyId','supplierPartyId',
     'shipmentMethodTypeId','carrierPartyId'].each { f ->
        if (parameters[f]) storeCtx[f] = parameters[f]
    }

    // ── Complete role set — SECAs (createPaymentFromOrder, invoicing) need
    //    BILL_TO and BILL_FROM parties on every order ──────────────────────────
    String orderTypeId = storeCtx.orderTypeId
    // internal organisation (Company) — from general.properties ORGANIZATION_PARTY
    String orgPartyId = org.apache.ofbiz.entity.util.EntityUtilProperties
            .getPropertyValue('general', 'ORGANIZATION_PARTY', 'Company', delegator)

    if (orderTypeId == 'SALES_ORDER') {
        // customer side — default every customer role to the order party
        storeCtx.placingCustomerPartyId = storeCtx.placingCustomerPartyId ?: storeCtx.partyId
        storeCtx.billToCustomerPartyId  = storeCtx.billToCustomerPartyId  ?: storeCtx.partyId
        storeCtx.shipToCustomerPartyId  = storeCtx.shipToCustomerPartyId  ?: storeCtx.partyId
        storeCtx.endUserCustomerPartyId = storeCtx.endUserCustomerPartyId ?: storeCtx.partyId
        // company side — bill-from = product store payToPartyId, else Company
        if (!storeCtx.billFromVendorPartyId) {
            String payTo = null
            if (storeCtx.productStoreId) {
                GenericValue store = delegator.findOne('ProductStore',
                        [productStoreId: storeCtx.productStoreId], true)
                payTo = store?.getString('payToPartyId')
            }
            storeCtx.billFromVendorPartyId = payTo ?: orgPartyId
        }
    } else {  // PURCHASE_ORDER
        // vendor side — default every vendor role to the order party
        storeCtx.billFromVendorPartyId = storeCtx.billFromVendorPartyId ?: storeCtx.partyId
        storeCtx.shipFromVendorPartyId = storeCtx.shipFromVendorPartyId ?: storeCtx.partyId
        storeCtx.supplierAgentPartyId  = storeCtx.supplierAgentPartyId  ?: storeCtx.partyId
        // company side — we are the customer buying
        storeCtx.billToCustomerPartyId = storeCtx.billToCustomerPartyId ?: orgPartyId
        storeCtx.placingCustomerPartyId = storeCtx.placingCustomerPartyId ?: orgPartyId
        storeCtx.shipToCustomerPartyId  = storeCtx.shipToCustomerPartyId  ?: orgPartyId
        storeCtx.endUserCustomerPartyId = storeCtx.endUserCustomerPartyId ?: orgPartyId
    }

    if (orderNotes)  storeCtx.orderNotes            = orderNotes
    if (promoCodes)  storeCtx.orderProductPromoCodes = promoCodes

    // ── Default ship group — required for Quick Ship / fulfillment ───────────
    // Resolve ship-to address: sales → customer address; purchase → company address
    String shipToParty = (orderTypeId == 'SALES_ORDER') ? storeCtx.partyId : storeCtx.billToCustomerPartyId
    // explicit caller-selected ship-to address wins; else default-purpose fallback
    String shipContactMechId = parameters.shipToContactMechId ?: null
    if (!shipContactMechId && shipToParty) {
        for (String purpose in ['SHIPPING_LOCATION', 'GENERAL_LOCATION', 'BILLING_LOCATION']) {
            def pcmp = delegator.findByAnd('PartyContactMechPurpose',
                    [partyId: shipToParty, contactMechPurposeTypeId: purpose], null, false)
                    .find { it.getTimestamp('thruDate') == null }
            if (pcmp) { shipContactMechId = pcmp.getString('contactMechId'); break }
        }
    }
    GenericValue shipGroup = delegator.makeValue('OrderItemShipGroup')
    shipGroup.set('shipGroupSeqId',       '00001')
    shipGroup.set('shipmentMethodTypeId', parameters.shipmentMethodTypeId ?: 'STANDARD')
    shipGroup.set('carrierPartyId',       parameters.carrierPartyId ?: '_NA_')
    shipGroup.set('carrierRoleTypeId',    'CARRIER')
    shipGroup.set('maySplit',             'N')
    shipGroup.set('isGift',               'N')
    if (shipContactMechId) shipGroup.set('contactMechId', shipContactMechId)

    List<GenericValue> shipGroupInfo = [shipGroup]
    orderItems.each { oi ->
        GenericValue assoc = delegator.makeValue('OrderItemShipGroupAssoc')
        assoc.set('orderItemSeqId', oi.getString('orderItemSeqId'))
        assoc.set('shipGroupSeqId', '00001')
        assoc.set('quantity',       oi.getBigDecimal('quantity'))
        shipGroupInfo << assoc
    }
    storeCtx.orderItemShipGroupInfo = shipGroupInfo

    Map res = dispatcher.runSync('storeOrder', storeCtx)
    if (res.responseCode == 'error' || res._ERROR_MESSAGE_) {
        return error(res._ERROR_MESSAGE_ ?: 'storeOrder failed')
    }
    if (res.responseCode == 'fail' || res._ERROR_MESSAGE_LIST_) {
        return failure(res._ERROR_MESSAGE_LIST_?.join('; ') ?: 'storeOrder returned failure')
    }

    // Sales orders need OrderHeader.originFacilityId for downstream flows
    // (allocation plan approve/update re-reserves against it). Stock checkout
    // sets it from the store; mirror that via the standard updateOrderHeader.
    if (res.orderId && parameters.productStoreId) {
        GenericValue store = from('ProductStore').where('productStoreId', parameters.productStoreId).queryOne()
        if (store?.inventoryFacilityId) {
            dispatcher.runSync('updateOrderHeader',
                [orderId: res.orderId, originFacilityId: store.inventoryFacilityId, userLogin: parameters.userLogin])
        }
    }

    return success([
        orderId:         res.orderId,
        statusId:        res.statusId,
        grandTotal:      grandTotal,
        subtotal:        calc.subtotal,
        taxTotal:        calc.taxTotal,
        discountTotal:   calc.discountTotal,
        shippingTotal:   calc.shippingTotal,
    ])
}

// ─────────────────────────────────────────────────────────────────────────────
// ORDER ATTACHMENTS — REST-safe upload/download (base64 <-> ByteBuffer bridge)
// Native OFBiz chain: createContentFromUploadedFile (DataResource+Content)
//                     + createOrderContent — same as ordermgr addOrderAttachments
// ─────────────────────────────────────────────────────────────────────────────

Map uiUploadOrderAttachment() {
    String orderId  = parameters.orderId
    String fileName = parameters.fileName
    String mimeType = parameters.mimeType ?: 'application/octet-stream'
    String b64      = parameters.fileDataBase64
    if (!orderId)  return error('orderId is required')
    if (!fileName) return error('fileName is required')
    if (!b64)      return error('fileDataBase64 is required')

    byte[] bytes
    try { bytes = Base64.decoder.decode(b64) }
    catch (e) { return error('fileDataBase64 is not valid base64') }
    if (!bytes) return error('Uploaded file is empty')

    // Ensure the OrderContentType exists (runtime data — no seed changes)
    String contentTypeId = parameters.orderContentTypeId ?: 'ORDER_ATTACHMENT'
    if (!delegator.findOne('OrderContentType', [orderContentTypeId: contentTypeId], false)) {
        delegator.create('OrderContentType',
                [orderContentTypeId: contentTypeId, description: 'Order Attachment'])
    }

    // Pick storage entity by mime — same mapping attachUploadToDataResource uses
    String drType = mimeType.startsWith('image/') ? 'IMAGE_OBJECT'
                  : mimeType.startsWith('video/') ? 'VIDEO_OBJECT'
                  : mimeType.startsWith('audio/') ? 'AUDIO_OBJECT'
                  : 'OTHER_OBJECT'

    Map upCtx = [
        userLogin:                  parameters.userLogin,
        dataResourceTypeId:         drType,
        dataResourceName:           fileName,
        mimeTypeId:                 mimeType,
        uploadedFile:               java.nio.ByteBuffer.wrap(bytes),
        _uploadedFile_fileName:     fileName,
        _uploadedFile_contentType:  mimeType,
        contentName:                fileName,
        statusId:                   'CTNT_PUBLISHED',
    ]
    Map upRes = dispatcher.runSync('createContentFromUploadedFile', upCtx)
    if (upRes._ERROR_MESSAGE_) return error(upRes._ERROR_MESSAGE_)
    String contentId = upRes.contentId
    if (!contentId) return error('Upload did not return a contentId')

    Map ocCtx = [
        userLogin:          parameters.userLogin,
        orderId:            orderId,
        orderItemSeqId:     parameters.orderItemSeqId ?: '_NA_',
        orderContentTypeId: contentTypeId,
        contentId:          contentId,
        fromDate:           org.apache.ofbiz.base.util.UtilDateTime.nowTimestamp(),
    ]
    Map ocRes = dispatcher.runSync('createOrderContent', ocCtx)
    if (ocRes._ERROR_MESSAGE_) return error(ocRes._ERROR_MESSAGE_)

    return success([contentId: contentId, fileName: fileName])
}

Map uiGetOrderAttachment() {
    String contentId = parameters.contentId
    if (!contentId) return error('contentId is required')

    GenericValue content = delegator.findOne('Content', [contentId: contentId], false)
    if (!content) return error("Content ${contentId} not found")
    String drId = content.getString('dataResourceId')
    GenericValue dr = drId ? delegator.findOne('DataResource', [dataResourceId: drId], false) : null
    if (!dr) return error("DataResource for content ${contentId} not found")

    String drType = dr.getString('dataResourceTypeId')
    byte[] bytes = null
    switch (drType) {
        case 'IMAGE_OBJECT':
            bytes = delegator.findOne('ImageDataResource', [dataResourceId: drId], false)?.getBytes('imageData'); break
        case 'VIDEO_OBJECT':
            bytes = delegator.findOne('VideoDataResource', [dataResourceId: drId], false)?.getBytes('videoData'); break
        case 'AUDIO_OBJECT':
            bytes = delegator.findOne('AudioDataResource', [dataResourceId: drId], false)?.getBytes('audioData'); break
        case 'OTHER_OBJECT':
            bytes = delegator.findOne('OtherDataResource', [dataResourceId: drId], false)?.getBytes('dataResourceContent'); break
        default:
            // LOCAL_FILE and friends — read from disk path in objectInfo
            String path = dr.getString('objectInfo')
            if (path) { File f = new File(path); if (f.exists()) bytes = f.bytes }
    }
    if (bytes == null) return error("No binary data found for content ${contentId} (type ${drType})")

    return success([
        fileName:       dr.getString('dataResourceName') ?: contentId,
        mimeType:       dr.getString('mimeTypeId') ?: 'application/octet-stream',
        fileDataBase64: Base64.encoder.encodeToString(bytes),
    ])
}

// ─────────────────────────────────────────────────────────────────────────────
// ORDER ITEM INVENTORY SUMMARY — reproduces OrderView.groovy context exactly:
//   getProductInventorySummaryForItems (all facilities + store facility),
//   getProductManufacturingSummaryByFacility (In Production),
//   InventoryWorker outstanding SO/PO quantities (Required for SO / On Order),
//   unplanned = required - qohAll - inProduction - onOrder - mktgPkgQOH  (>=0)
// Display condition (same as OrderItems.ftl): orderHeader.statusId != ORDER_COMPLETED
// ─────────────────────────────────────────────────────────────────────────────
Map uiGetOrderItemInventorySummary() {
    String orderId = parameters.orderId
    if (!orderId) return error('orderId is required')

    GenericValue orderHeader = delegator.findOne('OrderHeader', [orderId: orderId], false)
    if (!orderHeader) return error("Order ${orderId} not found")
    if (orderHeader.getString('statusId') == 'ORDER_COMPLETED') {
        return success([inventorySummary: [], facilityName: null, facilityId: null])
    }

    List<GenericValue> orderItems = delegator.findByAnd('OrderItem', [orderId: orderId], null, false)
    List productIds = orderItems*.getString('productId').findAll { it }.unique()
    if (!productIds) return success([inventorySummary: [], facilityName: null, facilityId: null])

    // all-facility summary
    Map inv = dispatcher.runSync('getProductInventorySummaryForItems',
            [orderItems: orderItems, userLogin: parameters.userLogin])
    Map atpAll  = inv.availableToPromiseMap ?: [:]
    Map qohAll  = inv.quantityOnHandMap ?: [:]
    Map mktgQoh = inv.mktgPkgQOHMap ?: [:]

    // store-facility summary
    String facilityId = null, facilityName = null
    Map atpFac = [:], qohFac = [:]
    String storeId = orderHeader.getString('productStoreId')
    if (storeId) {
        GenericValue store = delegator.findOne('ProductStore', [productStoreId: storeId], true)
        facilityId = store?.getString('inventoryFacilityId')
        if (facilityId) {
            GenericValue fac = delegator.findOne('Facility', [facilityId: facilityId], true)
            facilityName = fac?.getString('facilityName') ?: facilityId
            Map invFac = dispatcher.runSync('getProductInventorySummaryForItems',
                    [orderItems: orderItems, facilityId: facilityId, userLogin: parameters.userLogin])
            atpFac = invFac.availableToPromiseMap ?: [:]
            qohFac = invFac.quantityOnHandMap ?: [:]
        }
    }

    // in production (manufacturing) — plugin may be absent, treat as 0
    Map production = [:]
    productIds.each { pid ->
        try {
            Map mr = dispatcher.runSync('getProductManufacturingSummaryByFacility',
                    [productId: pid, userLogin: parameters.userLogin])
            BigDecimal q = BigDecimal.ZERO
            (mr.summaryInByFacility ?: [:]).values().each { m ->
                q += (m.estimatedQuantityTotal ?: BigDecimal.ZERO) }
            production[pid] = q
        } catch (e) { production[pid] = BigDecimal.ZERO }
    }

    // outstanding SO (required) / outstanding PO (on order)
    Map required = [:], onOrder = [:]
    try {
        required = org.apache.ofbiz.product.inventory.InventoryWorker
                .getOutstandingProductQuantitiesForSalesOrders(productIds as Set, delegator)
        onOrder = org.apache.ofbiz.product.inventory.InventoryWorker
                .getOutstandingProductQuantitiesForPurchaseOrders(productIds as Set, delegator)
    } catch (e) { /* keep empty maps */ }

    List rows = productIds.collect { pid ->
        BigDecimal req  = (required[pid] ?: BigDecimal.ZERO) as BigDecimal
        BigDecimal qAll = (qohAll[pid] ?: BigDecimal.ZERO) as BigDecimal
        BigDecimal prod = (production[pid] ?: BigDecimal.ZERO) as BigDecimal
        BigDecimal onO  = (onOrder[pid] ?: BigDecimal.ZERO) as BigDecimal
        BigDecimal mQoh = (mktgQoh[pid] ?: BigDecimal.ZERO) as BigDecimal
        BigDecimal unplanned = req - qAll - prod - onO - mQoh
        if (unplanned < BigDecimal.ZERO) unplanned = BigDecimal.ZERO
        [productId:      pid,
         requiredForSO:  req,
         qohFacility:    qohFac[pid] ?: BigDecimal.ZERO,
         atpFacility:    atpFac[pid] ?: BigDecimal.ZERO,
         qohAll:         qAll,
         atpAll:         atpAll[pid] ?: BigDecimal.ZERO,
         onOrder:        onO,
         inProduction:   prod,
         unplanned:      unplanned]
    }
    return success([inventorySummary: rows, facilityName: facilityName, facilityId: facilityId])
}

// ── Helpers ───────────────────────────────────────────────────────────────────
private static BigDecimal toBD(val) {
    if (val == null) return null
    if (val instanceof BigDecimal) return val
    try { return new BigDecimal(val.toString()) } catch (ignored) { return null }
}

private static Timestamp toTimestamp(val) {
    if (!val) return null
    if (val instanceof Timestamp) return val
    try { return new Timestamp(Long.parseLong(val.toString())) } catch (ignored) {}
    try { return Timestamp.valueOf(val.toString().replace('T', ' ').take(19)) } catch (ignored) {}
    return null
}

