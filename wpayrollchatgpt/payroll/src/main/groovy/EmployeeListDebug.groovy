import org.apache.ofbiz.entity.util.EntityQuery

def delegator = context.delegator
def dispatcher = context.dispatcher

def employeeList = EntityQuery.use(delegator)
        .from("EmployeeMasterListView")
        .queryList()

context.employeeList = employeeList
context.employeeListSize = employeeList?.size() ?: 0

return "success"
