import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.service.ServiceUtil
def uiGetGlAccountList(ctx) {
    def results = []
    try {
        int viewSize  = parameters?.viewSize  ? Integer.parseInt(parameters.viewSize.toString())  : 50
        int viewIndex = parameters?.viewIndex ? Integer.parseInt(parameters.viewIndex.toString()) : 0
        String glAccountId     = parameters?.glAccountId     ?: ''
        String accountCode     = parameters?.accountCode     ?: ''
        String accountName     = parameters?.accountName     ?: ''
        String glAccountTypeId = parameters?.glAccountTypeId ?: ''
        List conds = []
        if (glAccountId)     conds.add(EntityCondition.makeCondition('glAccountId',     EntityOperator.LIKE,   glAccountId + '%'))
        if (glAccountTypeId) conds.add(EntityCondition.makeCondition('glAccountTypeId', EntityOperator.EQUALS, glAccountTypeId))
        if (accountName)     conds.add(EntityCondition.makeCondition('accountName',     EntityOperator.LIKE,   accountName + '%'))
        if (accountCode)     conds.add(EntityCondition.makeCondition('accountCode',     EntityOperator.LIKE,   accountCode + '%'))
        def qb = EntityQuery.use(delegator).from('GlAccount').orderBy('glAccountId ASC').maxRows(viewSize).cursorScrollInsensitive()
        if (conds) qb = qb.where(EntityCondition.makeCondition(conds, EntityOperator.AND))
        def gList = qb.queryList()
        def qc = EntityQuery.use(delegator).from('GlAccount')
        if (conds) qc = qc.where(EntityCondition.makeCondition(conds, EntityOperator.AND))
        int total = (int) qc.queryCount()
        gList.each { g ->
            results.add([
                glAccountId:      g.glAccountId      ?: '',
                accountCode:      g.accountCode      ?: '',
                accountName:      g.accountName      ?: '',
                glAccountTypeId:  g.glAccountTypeId  ?: '',
                glAccountClassId: g.glAccountClassId ?: '',
                description:      g.description      ?: ''
            ])
        }
        def r = ServiceUtil.returnSuccess()
        r.glAccountList = results
        r.totalCount    = total
        r.viewSize      = viewSize
        r.viewIndex     = viewIndex
        return r
    } catch (Exception ex) {
        return ServiceUtil.returnError('uiGetGlAccountList error: ' + ex.message)
    }
}
