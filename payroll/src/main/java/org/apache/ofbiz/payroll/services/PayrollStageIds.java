// File: plugins/payroll/src/main/java/org/apache/ofbiz/payroll/services/PayrollStageIds.java
package org.apache.ofbiz.payroll.services;

/**
 * Stage + status constants used by PayrollSchedulerServicesV2 and response builder.
 * Keep IDs stable (API contract).
 */
public final class PayrollStageIds {

    private PayrollStageIds() {}

public static final String GENERATE_BANK = "GENERATE_BANK";
    // =========================================================
    // Stage IDs (pipeline)
    // =========================================================
    public static final String STAGE_RESOLVE    = "RESOLVE";      // optional pre-stage (resolve run/period)
    public static final String STAGE_VALIDATE   = "VALIDATE";
    public static final String STAGE_CALCULATE  = "CALCULATE";
    public static final String STAGE_PROCESS    = "PROCESS";      // optional: if you separate calculate vs persist
    public static final String STAGE_POST_GL    = "POST_GL";
    public static final String STAGE_POST_INV   = "POST_INV";
    public static final String STAGE_BANK       = "BANK";
    public static final String STAGE_EXPORT     = "EXPORT";
    public static final String STAGE_CLOSE      = "CLOSE";        // optional: close run/period

    // =========================================================
    // Aliases expected by PayrollSchedulerServicesV2
    // (your compile errors reference PayrollStageIds.VALIDATE, etc.)
    // =========================================================
    public static final String RESOLVE   = STAGE_RESOLVE;
    public static final String VALIDATE  = STAGE_VALIDATE;
    public static final String CALCULATE = STAGE_CALCULATE;
    public static final String PROCESS   = STAGE_PROCESS;
    public static final String POST_GL   = STAGE_POST_GL;
    public static final String POST_INV  = STAGE_POST_INV;
    public static final String BANK      = STAGE_BANK;
    public static final String EXPORT    = STAGE_EXPORT;
    public static final String CLOSE     = STAGE_CLOSE;
public static final String ROUTE_PAYMENTS = "ROUTE_PAYMENTS";
public static final String READINESS     = "READINESS";
    // =========================================================
    // Stage status IDs
    // =========================================================
    public static final String STG_OK   = "STG_OK";
    public static final String STG_WARN = "STG_WARN";
    public static final String STG_ERR  = "STG_ERR";
    public static final String STG_SKIP = "STG_SKIP"; // explicitly skipped by mode

    // =========================================================
    // Message severity IDs (align with PayrollRunResponseBuilder)
    // =========================================================
    public static final String MSG_INFO = "INFO";
    public static final String MSG_WARN = "WARN";
    public static final String MSG_ERR  = "ERROR";

    // =========================================================
    // Posting mode labels (REST)
    // =========================================================
    public static final String MODE_NONE = "NONE";
    public static final String MODE_GL   = "GL";
    public static final String MODE_INV  = "INVOICE";
    public static final String MODE_BANK = "BANK";

    // =========================================================
    // Common input flags
    // =========================================================
    public static final String YES = "Y";
    public static final String NO  = "N";

    // =========================================================
    // PayrollPeriod statusIds (used by resolver/validation)
    // =========================================================
    /** Your POC open period status. */
    // PayrollPeriod statuses (StatusType: PAY_PER_STAT)
public static final String PER_STATUS_OPEN   = "PER_OPEN";
public static final String PER_STATUS_CLOSED = "PER_CLOSED"; // if you have it

    // Generic payroll active/inactive (StatusType: PAY_STATUS)
public static final String PAY_STATUS_ACTIVE = "P_ACT";
public static final String PAY_STATUS_INACTIVE = "P_INA";



   
}
