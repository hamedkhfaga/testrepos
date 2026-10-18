import org.apache.ofbiz.entity.util.EntityQuery
import org.apache.ofbiz.entity.condition.EntityCondition
import org.apache.ofbiz.entity.condition.EntityOperator
import org.apache.ofbiz.service.ServiceUtil
def uiGetPartyList(ctx) {
    def results = []
    try {
        int viewSize   = parameters?.viewSize   ? Integer.parseInt(parameters.viewSize.toString())   : 50
        int viewIndex  = parameters?.viewIndex  ? Integer.parseInt(parameters.viewIndex.toString())  : 0
        String partyId      = parameters?.partyId      ?: ''
        String partyTypeId  = parameters?.partyTypeId  ?: ''
        String firstName    = parameters?.firstName    ?: ''
        String lastName     = parameters?.lastName     ?: ''
        String groupName    = parameters?.groupName    ?: ''
        List conds = []
        if (partyId)     conds.add(EntityCondition.makeCondition('partyId',     EntityOperator.LIKE,   partyId + '%'))
        if (partyTypeId) conds.add(EntityCondition.makeCondition('partyTypeId', EntityOperator.EQUALS, partyTypeId))
        if (firstName)   conds.add(EntityCondition.makeCondition('firstName',   EntityOperator.LIKE,   firstName + '%'))
        if (lastName)    conds.add(EntityCondition.makeCondition('lastName',    EntityOperator.LIKE,   lastName  + '%'))
        if (groupName)   conds.add(EntityCondition.makeCondition('groupName',   EntityOperator.LIKE,   groupName + '%'))
        def qb = EntityQuery.use(delegator).from('PartyAndPerson').orderBy('partyId ASC').maxRows(viewSize).cursorScrollInsensitive()
        if (conds) qb = qb.where(EntityCondition.makeCondition(conds, EntityOperator.AND))
        def pList = qb.queryList()
        def qc = EntityQuery.use(delegator).from('PartyAndPerson')
        if (conds) qc = qc.where(EntityCondition.makeCondition(conds, EntityOperator.AND))
        int total = (int) qc.queryCount()
        pList.each { p ->
            def ul = EntityQuery.use(delegator).from('UserLogin').where('partyId', p.partyId).queryFirst()
            results.add([partyId: p.partyId?:'', partyTypeId: p.partyTypeId?:'PERSON',
                firstName: p.firstName?:'', lastName: p.lastName?:'',
                statusId: p.statusId?:'PARTY_ENABLED',
                userLoginId: ul?.userLoginId?:''])
        }
        def r = ServiceUtil.returnSuccess()
        r.partyList = results; r.totalCount = total; r.viewSize = viewSize; r.viewIndex = viewIndex
        return r
    } catch (Exception ex) { return ServiceUtil.returnError('uiGetPartyList error: ' + ex.message) }
}
