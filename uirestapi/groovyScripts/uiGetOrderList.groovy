import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.service.ServiceUtil
def uiGetOrderList(ctx) {
    def results = []
    try {
        int viewSize  = parameters?.viewSize  ? Integer.parseInt(parameters.viewSize.toString())  : 50
        int viewIndex = parameters?.viewIndex ? Integer.parseInt(parameters.viewIndex.toString()) : 0
        String orderId      = parameters?.orderId      ?: ''
        String statusId     = parameters?.statusId     ?: ''
        String orderTypeId  = parameters?.orderTypeId  ?: ''
        String partyId      = parameters?.partyId      ?: ''
        List conds = []
        if (orderId)     conds.add(EntityCondition.makeCondition('orderId',     EntityOperator.LIKE,   orderId + '%'))
        if (statusId)    conds.add(EntityCondition.makeCondition('statusId',    EntityOperator.EQUALS, statusId))
        if (orderTypeId) conds.add(EntityCondition.makeCondition('orderTypeId', EntityOperator.EQUALS, orderTypeId))
        def qb = EntityQuery.use(delegator).from('OrderHeader').orderBy('orderDate DESC').maxRows(viewSize).cursorScrollInsensitive()
        if (conds) qb = qb.where(EntityCondition.makeCondition(conds, EntityOperator.AND))
        def oList = qb.queryList()
        def qc = EntityQuery.use(delegator).from('OrderHeader')
        if (conds) qc = qc.where(EntityCondition.makeCondition(conds, EntityOperator.AND))
        int total = (int) qc.queryCount()
        oList.each { o ->
            results.add([orderId: o.orderId?:'', orderTypeId: o.orderTypeId?:'',
                statusId: o.statusId?:'', orderDate: o.orderDate?o.orderDate.toString():'',
                grandTotal: o.grandTotal?o.grandTotal.toString():'0',
                currencyUom: o.currencyUom?:'USD', orderName: o.orderName?:'',
                salesChannelEnumId: o.salesChannelEnumId?:''])
        }
        def r = ServiceUtil.returnSuccess()
        r.orderList = results; r.totalCount = total; r.viewSize = viewSize; r.viewIndex = viewIndex
        return r
    } catch (Exception ex) { return ServiceUtil.returnError('uiGetOrderList error: ' + ex.message) }
}
