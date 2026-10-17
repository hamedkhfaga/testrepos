 package org.apache.ofbiz.payroll.util;

public final class PayrollStatusIds {
    private PayrollStatusIds() {}

    // Assignment
    public static final String ASG_ACTIVE = "PAY_ASG_ACT";
    public static final String ASG_INACTIVE = "PAY_ASG_INA";
    public static final String ASG_ENDED = "PAY_ASG_END";

    // Generic
    public static final String ACT = "P_ACT";
    public static final String INA = "P_INA";

    // Structure
    public static final String STR_ACTIVE = "PAYSTR_ACT";
    public static final String STR_INACTIVE = "PAYSTR_INA";
    public static final String STR_DRAFT = "PAYSTR_DRF";

    // Payroll Run (ONLY PR_*)
	public static final String RUN_NEW        = "PR_NEW";
    public static final String RUN_DRAFT = "PR_DRAFT";
    public static final String RUN_SUBM  = "PR_SUBM";
    public static final String RUN_APPR  = "PR_APPR";
    public static final String RUN_PROC  = "PR_PROC";
    public static final String RUN_DONE  = "PR_DONE";
    public static final String RUN_POST  = "PR_POST";
    public static final String RUN_FAIL  = "PR_FAIL";
    public static final String RUN_VOID  = "PR_VOID";
	public static final String RUN_PROCESSED = "PR_PROCESSED";
	
	public static final String PAYSLIP_DRAFT = "PPS_DRAFT";
public static final String PAYSLIP_GEN   = "PPS_GEN";
public static final String PAYSLIP_ERR   = "PPS_ERR";

public static final String PAYSLIP_FMT_HTML = "PLS_HTML";
public static final String PAYSLIP_FMT_PDF  = "PLS_PDF";

public static final String AUDIT_PAYSLIP_GEN = "PA_ACT_PAYSLIP_GEN";
public static final String AUDIT_RECON       = "PA_ACT_RECON";
public static final String AUDIT_REPORT_RUN  = "PA_ACT_REPORT_RUN";

}

  
   