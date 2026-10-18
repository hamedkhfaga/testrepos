import org.apache.ofbiz.service.ServiceUtil
def uiDebug(ctx) {
    def helper = delegator.getEntityHelper('org.apache.ofbiz')
    def hm = helper.class.methods.collect { m -> m.name + '(' + m.parameterTypes.collect{t->t.simpleName}.join(',') + ')' }.sort().unique().findAll { it.toLowerCase().contains('connect') || it.toLowerCase().contains('datasource') || it.toLowerCase().contains('sql') }
    def dm = delegator.class.methods.collect { m -> m.name + '(' + m.parameterTypes.collect{t->t.simpleName}.join(',') + ')' }.sort().unique().findAll { it.toLowerCase().contains('connect') || it.toLowerCase().contains('datasource') || it.toLowerCase().contains('helper') }
    def r = ServiceUtil.returnSuccess()
    r.helperClass = helper.class.name
    r.helperMethods = hm
    r.delegatorMethods = dm
    return r
}