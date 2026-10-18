import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.service.ServiceUtil
def uiGetProductList(ctx) {
    def results = []
    try {
        int viewSize  = parameters?.viewSize  ? Integer.parseInt(parameters.viewSize.toString())  : 50
        int viewIndex = parameters?.viewIndex ? Integer.parseInt(parameters.viewIndex.toString()) : 0
        String productId      = parameters?.productId      ?: ''
        String productTypeId  = parameters?.productTypeId  ?: ''
        String internalName   = parameters?.internalName   ?: ''
        List conds = []
        if (productId)     conds.add(EntityCondition.makeCondition('productId',     EntityOperator.LIKE,   productId + '%'))
        if (productTypeId) conds.add(EntityCondition.makeCondition('productTypeId', EntityOperator.EQUALS, productTypeId))
        if (internalName)  conds.add(EntityCondition.makeCondition('internalName',  EntityOperator.LIKE,   internalName + '%'))
        def qb = EntityQuery.use(delegator).from('Product').orderBy('productId ASC').maxRows(viewSize).cursorScrollInsensitive()
        if (conds) qb = qb.where(EntityCondition.makeCondition(conds, EntityOperator.AND))
        def prList = qb.queryList()
        def qc = EntityQuery.use(delegator).from('Product')
        if (conds) qc = qc.where(EntityCondition.makeCondition(conds, EntityOperator.AND))
        int total = (int) qc.queryCount()
        prList.each { p ->
            results.add([productId: p.productId?:'', productTypeId: p.productTypeId?:'',
                internalName: p.internalName?:'', brandName: p.brandName?:'',
                description: p.description?:'', isVirtual: p.isVirtual?:'N',
                salesDiscontinuationDate: p.salesDiscontinuationDate?p.salesDiscontinuationDate.toString():''])
        }
        def r = ServiceUtil.returnSuccess()
        r.productList = results; r.totalCount = total; r.viewSize = viewSize; r.viewIndex = viewIndex
        return r
    } catch (Exception ex) { return ServiceUtil.returnError('uiGetProductList error: ' + ex.message) }
}
