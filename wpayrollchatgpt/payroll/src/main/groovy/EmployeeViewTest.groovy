import org.apache.ofbiz.entity.util.EntityQuery

def delegator = context.delegator

def list = EntityQuery.use(delegator)
        .from("EmployeeMasterListView")
        .queryList()

println "Employee count = " + list.size()

list.each {
    println it.partyId + " | " +
            it.firstName + " " + it.lastName + " | " +
            it.department
}

return "success"
