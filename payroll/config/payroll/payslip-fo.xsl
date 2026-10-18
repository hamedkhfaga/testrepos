<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0"
  xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
  xmlns:fo="http://www.w3.org/1999/XSL/Format">
  <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

  <xsl:template match="/payslip">
    <fo:root>
      <fo:layout-master-set>
        <fo:simple-page-master master-name="A4"
          page-height="29.7cm" page-width="21cm" margin="1cm">
          <fo:region-body/>
        </fo:simple-page-master>
      </fo:layout-master-set>

      <fo:page-sequence master-reference="A4">
        <fo:flow flow-name="xsl-region-body">

          <fo:table width="100%" table-layout="fixed">
            <fo:table-column column-width="70%"/>
            <fo:table-column column-width="30%"/>
            <fo:table-body>
              <fo:table-row>
                <fo:table-cell>
                  <fo:block font-size="14pt" font-weight="bold">Payslip</fo:block>
                  <fo:block font-size="10pt">Employee: <xsl:value-of select="employeeName"/></fo:block>
                  <fo:block font-size="10pt">Employee ID: <xsl:value-of select="partyId"/></fo:block>
                  <fo:block font-size="10pt">Payroll Result: <xsl:value-of select="payrollResultId"/></fo:block>
                  <fo:block font-size="10pt">Run: <xsl:value-of select="payrollRunId"/></fo:block>
                  <fo:block font-size="10pt">Currency: <xsl:value-of select="currencyUomId"/></fo:block>
                </fo:table-cell>

                <fo:table-cell>
                  <fo:block text-align="right" space-after="4pt">
                    <fo:external-graphic content-width="5cm" content-height="1.2cm">
                      <xsl:attribute name="src">
                        <xsl:text>url('</xsl:text>
                        <xsl:value-of select="barcode/code128Url"/>
                        <xsl:text>')</xsl:text>
                      </xsl:attribute>
                    </fo:external-graphic>
                  </fo:block>

                  <fo:block text-align="right" space-after="4pt">
                    <fo:external-graphic content-width="2.5cm" content-height="2.5cm">
                      <xsl:attribute name="src">
                        <xsl:text>url('</xsl:text>
                        <xsl:value-of select="barcode/qrUrl"/>
                        <xsl:text>')</xsl:text>
                      </xsl:attribute>
                    </fo:external-graphic>
                  </fo:block>
                </fo:table-cell>

              </fo:table-row>
            </fo:table-body>
          </fo:table>

          <fo:block space-after="8pt"/>

          <fo:table width="60%" table-layout="fixed" border="0.5pt solid #000">
            <fo:table-column column-width="50%"/>
            <fo:table-column column-width="50%"/>
            <fo:table-body>
              <fo:table-row>
                <fo:table-cell border="0.5pt solid #000"><fo:block font-weight="bold" font-size="10pt" padding="4pt">Gross</fo:block></fo:table-cell>
                <fo:table-cell border="0.5pt solid #000"><fo:block font-size="10pt" padding="4pt"><xsl:value-of select="totals/grossSalary"/></fo:block></fo:table-cell>
              </fo:table-row>
              <fo:table-row>
                <fo:table-cell border="0.5pt solid #000"><fo:block font-weight="bold" font-size="10pt" padding="4pt">Deductions</fo:block></fo:table-cell>
                <fo:table-cell border="0.5pt solid #000"><fo:block font-size="10pt" padding="4pt"><xsl:value-of select="totals/totalDeductions"/></fo:block></fo:table-cell>
              </fo:table-row>
              <fo:table-row>
                <fo:table-cell border="0.5pt solid #000"><fo:block font-weight="bold" font-size="10pt" padding="4pt">Net</fo:block></fo:table-cell>
                <fo:table-cell border="0.5pt solid #000"><fo:block font-size="10pt" padding="4pt"><xsl:value-of select="totals/netSalary"/></fo:block></fo:table-cell>
              </fo:table-row>
            </fo:table-body>
          </fo:table>

          <fo:block space-after="10pt"/>

          <fo:block font-size="11pt" font-weight="bold" space-after="4pt">Breakdown</fo:block>

          <fo:table width="100%" table-layout="fixed" border="0.5pt solid #000" font-size="9pt">
            <fo:table-column column-width="18%"/>
            <fo:table-column column-width="52%"/>
            <fo:table-column column-width="15%"/>
            <fo:table-column column-width="15%"/>

            <fo:table-header>
              <fo:table-row font-weight="bold">
                <fo:table-cell border="0.5pt solid #000"><fo:block padding="4pt">Type</fo:block></fo:table-cell>
                <fo:table-cell border="0.5pt solid #000"><fo:block padding="4pt">Description</fo:block></fo:table-cell>
                <fo:table-cell border="0.5pt solid #000"><fo:block padding="4pt">Amount</fo:block></fo:table-cell>
                <fo:table-cell border="0.5pt solid #000"><fo:block padding="4pt">Curr</fo:block></fo:table-cell>
              </fo:table-row>
            </fo:table-header>

            <fo:table-body>
              <xsl:for-each select="lines/line">
                <fo:table-row>
                  <fo:table-cell border="0.5pt solid #000"><fo:block padding="3pt"><xsl:value-of select="lineTypeId"/></fo:block></fo:table-cell>
                  <fo:table-cell border="0.5pt solid #000"><fo:block padding="3pt"><xsl:value-of select="description"/></fo:block></fo:table-cell>
                  <fo:table-cell border="0.5pt solid #000"><fo:block padding="3pt"><xsl:value-of select="amount"/></fo:block></fo:table-cell>
                  <fo:table-cell border="0.5pt solid #000"><fo:block padding="3pt"><xsl:value-of select="currencyUomId"/></fo:block></fo:table-cell>
                </fo:table-row>
              </xsl:for-each>
            </fo:table-body>
          </fo:table>

        </fo:flow>
      </fo:page-sequence>
    </fo:root>
  </xsl:template>
</xsl:stylesheet>
