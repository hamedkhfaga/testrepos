<#-- XSL-FO template rendered by FreeMarker -->
<fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format">
  <fo:layout-master-set>
    <fo:simple-page-master master-name="A4"
                           page-height="29.7cm" page-width="21cm"
                           margin-top="1cm" margin-bottom="1cm"
                           margin-left="1.2cm" margin-right="1.2cm">
      <fo:region-body margin-top="1cm"/>
      <fo:region-before extent="1cm"/>
      <fo:region-after extent="0.8cm"/>
    </fo:simple-page-master>
  </fo:layout-master-set>

  <fo:page-sequence master-reference="A4">
    <fo:static-content flow-name="xsl-region-before">
      <fo:block font-size="12pt" font-weight="bold">Attendance Summary</fo:block>
      <fo:block font-size="9pt">Organization: ${orgId?xml}</fo:block>
      <fo:block font-size="9pt">Period: ${fromDate?xml} - ${thruDate?xml}</fo:block>
      <#if partyId?has_content>
        <fo:block font-size="9pt">Party: ${partyId?xml}</fo:block>
      </#if>
    </fo:static-content>

    <fo:static-content flow-name="xsl-region-after">
      <fo:block font-size="8pt" text-align="center">Page <fo:page-number/></fo:block>
    </fo:static-content>

    <fo:flow flow-name="xsl-region-body">
      <fo:block font-size="10pt" space-after="6pt"/>

      <fo:table table-layout="fixed" width="100%" border="0.2pt solid #444">
        <fo:table-column column-width="20%"/>
        <fo:table-column column-width="15%"/>
        <fo:table-column column-width="15%"/>
        <fo:table-column column-width="15%"/>
        <fo:table-column column-width="15%"/>
        <fo:table-column column-width="20%"/>

        <fo:table-header>
          <fo:table-row background-color="#f0f0f0">
            <fo:table-cell padding="3pt"><fo:block font-weight="bold">Party</fo:block></fo:table-cell>
            <fo:table-cell padding="3pt"><fo:block font-weight="bold">WorkDate</fo:block></fo:table-cell>
            <fo:table-cell padding="3pt"><fo:block font-weight="bold">Worked</fo:block></fo:table-cell>
            <fo:table-cell padding="3pt"><fo:block font-weight="bold">Overtime</fo:block></fo:table-cell>
            <fo:table-cell padding="3pt"><fo:block font-weight="bold">Late</fo:block></fo:table-cell>
            <fo:table-cell padding="3pt"><fo:block font-weight="bold">Early/Absent</fo:block></fo:table-cell>
          </fo:table-row>
        </fo:table-header>

        <fo:table-body>
          <#list rows as r>
            <fo:table-row>
              <fo:table-cell padding="3pt"><fo:block>${r.partyId?xml}</fo:block></fo:table-cell>
              <fo:table-cell padding="3pt"><fo:block>${r.workDate?xml}</fo:block></fo:table-cell>
              <fo:table-cell padding="3pt"><fo:block>${r.workedMinutes?c}</fo:block></fo:table-cell>
              <fo:table-cell padding="3pt"><fo:block>${r.overtimeMinutes?c}</fo:block></fo:table-cell>
              <fo:table-cell padding="3pt"><fo:block>${r.lateMinutes?c}</fo:block></fo:table-cell>
              <fo:table-cell padding="3pt">
                <fo:block>
                  Early: ${r.earlyLeaveMinutes?c}
                  <#if r.absenceFlag == "Y"> | Absent</#if>
                </fo:block>
              </fo:table-cell>
            </fo:table-row>
          </#list>
        </fo:table-body>
      </fo:table>

      <fo:block space-before="10pt" font-size="10pt" font-weight="bold">Totals</fo:block>
      <fo:block font-size="9pt">WorkedMinutes: ${totals.totalWorkedMinutes?c}</fo:block>
      <fo:block font-size="9pt">OvertimeMinutes: ${totals.totalOvertimeMinutes?c}</fo:block>
      <fo:block font-size="9pt">LateMinutes: ${totals.totalLateMinutes?c}</fo:block>
      <fo:block font-size="9pt">EarlyLeaveMinutes: ${totals.totalEarlyLeaveMinutes?c}</fo:block>
      <fo:block font-size="9pt">AbsentDays: ${totals.absentDays?c}</fo:block>

    </fo:flow>
  </fo:page-sequence>
</fo:root>
