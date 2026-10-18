import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.entity.jdbc.SQLProcessor
import org.apache.ofbiz.entity.datasource.GenericHelperInfo

def uiRunSql(ctx) {
    def sqlProc = null
    try {
        String sqlCommand  = parameters?.sqlCommand  ?: ''
        String grouperName = parameters?.grouperName ?: 'org.apache.ofbiz'
        int limitView = parameters?.limitView ? Integer.parseInt(parameters.limitView.toString()) : 200
        if (!sqlCommand.trim()) return ServiceUtil.returnError('SQL command is required')

        def helperName = delegator.getGroupHelperName(grouperName)
        def helperInfo = new GenericHelperInfo(grouperName, helperName)
        // OFBiz 18+ SQLProcessor constructor: (Delegator, GenericHelperInfo, Connection)
        sqlProc = new SQLProcessor(delegator, helperInfo, null)

        def rows = []
        def columns = []

        if (sqlCommand.trim().toUpperCase().startsWith('SELECT')) {
            sqlProc.prepareStatement(sqlCommand)
            sqlProc.executeQuery()
            def rs = sqlProc.getResultSet()
            def meta = rs.getMetaData()
            int colCount = meta.getColumnCount()
            colCount.times { i -> columns.add(meta.getColumnName(i + 1)) }
            while (rs.next()) {
                def row = [:]
                columns.each { col -> row[col] = rs.getString(col) ?: '' }
                rows.add(row)
            }
        } else {
            sqlProc.prepareStatement(sqlCommand)
            int affected = sqlProc.executeUpdate()
            columns = ['RESULT']
            rows = [['RESULT': affected.toString() + ' row(s) affected']]
        }
        def r = ServiceUtil.returnSuccess()
        r.rows = rows; r.columns = columns; r.rowCount = rows.size()
        return r
    } catch (Exception ex) {
        return ServiceUtil.returnError('SQL error: ' + ex.message)
    } finally {
        if (sqlProc) try { sqlProc.close() } catch(e) {}
    }
}
