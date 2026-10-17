import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.service.ServiceUtil
def uiGetInvoiceList(ctx) {
    def results = []
    try {
        int viewSize  = parameters?.viewSize  ? Integer.parseInt(parameters.viewSize.toString())  : 50
        int viewIndex = parameters?.viewIndex ? Integer.parseInt(parameters.viewIndex.toString()) : 0
        String invoiceId     = parameters?.invoiceId     ?: ''
        String statusId      = parameters?.statusId      ?: ''
        String invoiceTypeId = parameters?.invoiceTypeId ?: ''
        String partyId       = parameters?.partyId       ?: ''
        String partyIdFrom   = parameters?.partyIdFrom   ?: ''
        List conds = []
        if (invoiceId)     conds.add(EntityCondition.makeCondition('invoiceId',     EntityOperator.LIKE,   invoiceId + '%'))
        if (statusId)      conds.add(EntityCondition.makeCondition('statusId',      EntityOperator.EQUALS, statusId))
        if (invoiceTypeId) conds.add(EntityCondition.makeCondition('invoiceTypeId', EntityOperator.EQUALS, invoiceTypeId))
        if (partyId)       conds.add(EntityCondition.makeCondition('partyId',       EntityOperator.EQUALS, partyId))
        if (partyIdFrom)   conds.add(EntityCondition.makeCondition('partyIdFrom',   EntityOperator.EQUALS, partyIdFrom))
        def qb = EntityQuery.use(delegator).from('Invoice').orderBy('invoiceDate DESC').maxRows(viewSize).cursorScrollInsensitive()
        if (conds) qb = qb.where(EntityCondition.makeCondition(conds, EntityOperator.AND))
        def iList = qb.queryList()
        def qc = EntityQuery.use(delegator).from('Invoice')
        if (conds) qc = qc.where(EntityCondition.makeCondition(conds, EntityOperator.AND))
        int total = (int) qc.queryCount()
        iList.each { inv ->
            results.add([invoiceId: inv.invoiceId?:'', invoiceTypeId: inv.invoiceTypeId?:'',
                statusId: inv.statusId?:'', partyId: inv.partyId?:'', partyIdFrom: inv.partyIdFrom?:'',
                invoiceDate: inv.invoiceDate?inv.invoiceDate.toString():'',
                dueDate: inv.dueDate?inv.dueDate.toString():'',
                description: inv.description?:'', currencyUomId: inv.currencyUomId?:'USD'])
        }
        def r = ServiceUtil.returnSuccess()
        r.invoiceList = results; r.totalCount = total; r.viewSize = viewSize; r.viewIndex = viewIndex
        return r
    } catch (Exception ex) { return ServiceUtil.returnError('uiGetInvoiceList error: ' + ex.message) }
}
