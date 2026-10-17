<?xml version="1.0"?>
<?mso-application progid="Excel.Sheet"?>
<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet"
          xmlns:o="urn:schemas-microsoft-com:office:office"
          xmlns:x="urn:schemas-microsoft-com:office:excel"
          xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet">
  <Worksheet ss:Name="Attendance">
    <Table>
      <Row>
        <Cell><Data ss:Type="String">Organization</Data></Cell>
        <Cell><Data ss:Type="String">${orgId}</Data></Cell>
      </Row>
      <Row>
        <Cell><Data ss:Type="String">Period</Data></Cell>
        <Cell><Data ss:Type="String">${fromDate} - ${thruDate}</Data></Cell>
      </Row>
      <#if partyId?has_content>
      <Row>
        <Cell><Data ss:Type="String">Party</Data></Cell>
        <Cell><Data ss:Type="String">${partyId}</Data></Cell>
      </Row>
      </#if>

      <Row/>
      <Row>
        <Cell><Data ss:Type="String">PartyId</Data></Cell>
        <Cell><Data ss:Type="String">WorkDate</Data></Cell>
        <Cell><Data ss:Type="Number">Worked</Data></Cell>
        <Cell><Data ss:Type="Number">Overtime</Data></Cell>
        <Cell><Data ss:Type="Number">Late</Data></Cell>
        <Cell><Data ss:Type="Number">Early</Data></Cell>
        <Cell><Data ss:Type="String">Absent</Data></Cell>
      </Row>

      <#list rows as r>
      <Row>
        <Cell><Data ss:Type="String">${r.partyId}</Data></Cell>
        <Cell><Data ss:Type="String">${r.workDate}</Data></Cell>
        <Cell><Data ss:Type="Number">${r.workedMinutes?c}</Data></Cell>
        <Cell><Data ss:Type="Number">${r.overtimeMinutes?c}</Data></Cell>
        <Cell><Data ss:Type="Number">${r.lateMinutes?c}</Data></Cell>
        <Cell><Data ss:Type="Number">${r.earlyLeaveMinutes?c}</Data></Cell>
        <Cell><Data ss:Type="String">${r.absenceFlag}</Data></Cell>
      </Row>
      </#list>

      <Row/>
      <Row>
        <Cell><Data ss:Type="String">Totals</Data></Cell>
      </Row>
      <Row>
        <Cell><Data ss:Type="String">WorkedMinutes</Data></Cell>
        <Cell><Data ss:Type="Number">${totals.totalWorkedMinutes?c}</Data></Cell>
      </Row>
      <Row>
        <Cell><Data ss:Type="String">OvertimeMinutes</Data></Cell>
        <Cell><Data ss:Type="Number">${totals.totalOvertimeMinutes?c}</Data></Cell>
      </Row>
      <Row>
        <Cell><Data ss:Type="String">LateMinutes</Data></Cell>
        <Cell><Data ss:Type="Number">${totals.totalLateMinutes?c}</Data></Cell>
      </Row>
      <Row>
        <Cell><Data ss:Type="String">EarlyLeaveMinutes</Data></Cell>
        <Cell><Data ss:Type="Number">${totals.totalEarlyLeaveMinutes?c}</Data></Cell>
      </Row>
      <Row>
        <Cell><Data ss:Type="String">AbsentDays</Data></Cell>
        <Cell><Data ss:Type="Number">${totals.absentDays?c}</Data></Cell>
      </Row>

    </Table>
  </Worksheet>
</Workbook>
