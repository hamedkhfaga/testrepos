import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.service.ServiceUtil
def uiGetPaymentList(ctx) {
    def results = []
    try {
        int viewSize  = parameters?.viewSize  ? Integer.parseInt(parameters.viewSize.toString())  : 50
        int viewIndex = parameters?.viewIndex ? Integer.parseInt(parameters.viewIndex.toString()) : 0
        String paymentId      = parameters?.paymentId      ?: ''
        String statusId       = parameters?.statusId       ?: ''
        String paymentTypeId  = parameters?.paymentTypeId  ?: ''
        String partyIdFrom    = parameters?.partyIdFrom    ?: ''
        String partyIdTo      = parameters?.partyIdTo      ?: ''
        List conds = []
        if (paymentId)     conds.add(EntityCondition.makeCondition('paymentId',     EntityOperator.LIKE,   paymentId + '%'))
        if (statusId)      conds.add(EntityCondition.makeCondition('statusId',      EntityOperator.EQUALS, statusId))
        if (paymentTypeId) conds.add(EntityCondition.makeCondition('paymentTypeId', EntityOperator.EQUALS, paymentTypeId))
        if (partyIdFrom)   conds.add(EntityCondition.makeCondition('partyIdFrom',   EntityOperator.EQUALS, partyIdFrom))
        if (partyIdTo)     conds.add(EntityCondition.makeCondition('partyIdTo',     EntityOperator.EQUALS, partyIdTo))
        def qb = EntityQuery.use(delegator).from('Payment').orderBy('effectiveDate DESC').maxRows(viewSize).cursorScrollInsensitive()
        if (conds) qb = qb.where(EntityCondition.makeCondition(conds, EntityOperator.AND))
        def pList = qb.queryList()
        def qc = EntityQuery.use(delegator).from('Payment')
        if (conds) qc = qc.where(EntityCondition.makeCondition(conds, EntityOperator.AND))
        int total = (int) qc.queryCount()
        pList.each { p ->
            results.add([paymentId: p.paymentId?:'', paymentTypeId: p.paymentTypeId?:'',
                statusId: p.statusId?:'', partyIdFrom: p.partyIdFrom?:'', partyIdTo: p.partyIdTo?:'',
                amount: p.amount?p.amount.toString():'0',
                effectiveDate: p.effectiveDate?p.effectiveDate.toString():'',
                currencyUomId: p.currencyUomId?:'USD', paymentRefNum: p.paymentRefNum?:''])
        }
        def r = ServiceUtil.returnSuccess()
        r.paymentList = results; r.totalCount = total; r.viewSize = viewSize; r.viewIndex = viewIndex
        return r
    } catch (Exception ex) { return ServiceUtil.returnError('uiGetPaymentList error: ' + ex.message) }
}
