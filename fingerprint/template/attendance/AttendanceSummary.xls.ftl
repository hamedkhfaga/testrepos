<?xml version="1.0"?>
<?mso-application progid="Excel.Sheet"?>
<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet"
          xmlns:o="urn:schemas-microsoft-com:office:office"
          xmlns:x="urn:schemas-microsoft-com:office:excel"
          xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet">
  
  <Styles>
    <Style ss:ID="HeaderStyle">
      <Font ss:Bold="1"/>
      <Interior ss:Color="#DDEBF7" ss:Pattern="Solid"/> </Style>
  </Styles>

  <Worksheet ss:Name="Attendance">
    <Table>
      <Row>
        <Cell ss:StyleID="HeaderStyle"><Data ss:Type="String">Organization</Data></Cell>
        <Cell><Data ss:Type="String">${orgId!}</Data></Cell>
      </Row>
      <Row>
        <Cell ss:StyleID="HeaderStyle"><Data ss:Type="String">Period</Data></Cell>
        <Cell><Data ss:Type="String">${fromDate!} - ${thruDate!}</Data></Cell>
      </Row>
      
      <#if partyId?has_content>
      <Row>
        <Cell ss:StyleID="HeaderStyle"><Data ss:Type="String">Party</Data></Cell>
        <Cell><Data ss:Type="String">${partyId}</Data></Cell>
      </Row>
      </#if>

      <Row/><Row ss:StyleID="HeaderStyle">
        <Cell><Data ss:Type="String">PartyId</Data></Cell>
        <Cell><Data ss:Type="String">WorkDate</Data></Cell>
        <Cell><Data ss:Type="String">Worked</Data></Cell>
        <Cell><Data ss:Type="String">Overtime</Data></Cell>
        <Cell><Data ss:Type="String">Late</Data></Cell>
        <Cell><Data ss:Type="String">Early</Data></Cell>
        <Cell><Data ss:Type="String">Absent</Data></Cell>
      </Row>

      <#if rows?has_content>
          <#list rows as r>
          <Row>
            <Cell><Data ss:Type="String">${r.partyId!}</Data></Cell>
            <Cell><Data ss:Type="String">${r.workDate!}</Data></Cell>
            <Cell><Data ss:Type="Number">${(r.workedMinutes!0)?c}</Data></Cell>
            <Cell><Data ss:Type="Number">${(r.overtimeMinutes!0)?c}</Data></Cell>
            <Cell><Data ss:Type="Number">${(r.lateMinutes!0)?c}</Data></Cell>
            <Cell><Data ss:Type="Number">${(r.earlyLeaveMinutes!0)?c}</Data></Cell>
            <Cell><Data ss:Type="String">${r.absenceFlag!"N"}</Data></Cell>
          </Row>
          </#list>
      </#if>

      <Row/><Row><Cell ss:StyleID="HeaderStyle"><Data ss:Type="String">Totals</Data></Cell></Row>
      <#if totals??>
          <Row>
            <Cell><Data ss:Type="String">Worked Minutes</Data></Cell>
            <Cell><Data ss:Type="Number">${(totals.totalWorkedMinutes!0)?c}</Data></Cell>
          </Row>
          <Row>
            <Cell><Data ss:Type="String">Absent Days</Data></Cell>
            <Cell><Data ss:Type="Number">${(totals.absentDays!0)?c}</Data></Cell>
          </Row>
      </#if>
    </Table>
  </Worksheet>
</Workbook>