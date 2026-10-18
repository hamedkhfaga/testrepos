package org.apache.ofbiz.payroll.export;

public class PayslipFoBuilder {

    public static String buildFo(String employeeName,
                                 String employeeId,
                                 String period,
                                 String barcodeValue,
                                 String netAmount) {

        return "<?xml version='1.0' encoding='UTF-8'?>"
            + "<fo:root xmlns:fo='http://www.w3.org/1999/XSL/Format'>"
            + "<fo:layout-master-set>"
            + "<fo:simple-page-master master-name='A4'"
            + " page-height='29.7cm' page-width='21cm' margin='2cm'>"
            + "<fo:region-body/>"
            + "</fo:simple-page-master>"
            + "</fo:layout-master-set>"

            + "<fo:page-sequence master-reference='A4'>"
            + "<fo:flow flow-name='xsl-region-body'>"

            + "<fo:block font-size='14pt' font-weight='bold'>Payslip</fo:block>"
            + "<fo:block>Employee: " + employeeName + " (" + employeeId + ")</fo:block>"
            + "<fo:block>Period: " + period + "</fo:block>"

            + "<fo:block margin-top='10pt'>"
            + "<fo:external-graphic src='barcode:" + barcodeValue + "'/>"
            + "</fo:block>"

            + "<fo:block margin-top='20pt' font-size='12pt'>"
            + "Net Salary: " + netAmount
            + "</fo:block>"

            + "</fo:flow>"
            + "</fo:page-sequence>"
            + "</fo:root>";
    }
}
