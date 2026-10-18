import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.service.ServiceUtil
def uiGetEmployeeList(ctx) {
    def results = []
    try {
        int viewSize  = parameters?.viewSize  ? Integer.parseInt(parameters.viewSize.toString())  : 50
        int viewIndex = parameters?.viewIndex ? Integer.parseInt(parameters.viewIndex.toString()) : 0
        String firstName = parameters?.firstName ?: ''
        String lastName  = parameters?.lastName  ?: ''
        String partyId   = parameters?.partyId   ?: ''
        List conds = [EntityCondition.makeCondition('roleTypeId', EntityOperator.EQUALS, 'EMPLOYEE')]
        if (firstName) conds.add(EntityCondition.makeCondition('firstName', EntityOperator.LIKE, firstName + '%'))
        if (lastName)  conds.add(EntityCondition.makeCondition('lastName',  EntityOperator.LIKE, lastName  + '%'))
        if (partyId)   conds.add(EntityCondition.makeCondition('partyId',   EntityOperator.LIKE, partyId   + '%'))
        def cond  = EntityCondition.makeCondition(conds, EntityOperator.AND)
        def pList = EntityQuery.use(delegator).from('PartyRoleAndPartyDetail').where(cond).orderBy('partyId ASC').maxRows(viewSize).cursorScrollInsensitive().queryList()
        int total = (int) EntityQuery.use(delegator).from('PartyRoleAndPartyDetail').where(cond).queryCount()
        pList.each { p ->
            def status = p.statusId == 'PARTY_ENABLED' ? 'EMPL_ACTIVE' : (p.statusId ?: 'EMPL_ACTIVE')
            def e = [partyId: p.partyId?:'', firstName: p.firstName?:'', lastName: p.lastName?:'', partyTypeId: p.partyTypeId?:'PERSON', statusId: status]
            def ul = EntityQuery.use(delegator).from('UserLogin').where('partyId', p.partyId).queryFirst()
            e.userLoginId = ul?.userLoginId ?: ''
            results.add(e)
        }
        def r = ServiceUtil.returnSuccess()
        r.employeeList = results; r.totalCount = total; r.viewSize = viewSize; r.viewIndex = viewIndex
        return r
    } catch (Exception ex) { return ServiceUtil.returnError('uiGetEmployeeList error: ' + ex.message) }
}
