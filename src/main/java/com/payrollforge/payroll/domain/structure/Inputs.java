package com.payrollforge.payroll.domain.structure;

import java.util.List;

/**
 * Variables a formula may read besides other components. The payroll run fills every one of them for every
 * employee, so a formula never meets a missing value at run time.
 */
public final class Inputs {

    public static final String CTC_ANNUAL = "CTC_ANNUAL";
    /** Monthly CTC for this period, pro-rated across salary revisions inside the month. */
    public static final String CTC_MONTHLY = "CTC_MONTHLY";
    public static final String PERIOD_DAYS = "PERIOD_DAYS";
    /** Days in the period while employed, less loss-of-pay days. */
    public static final String PAYABLE_DAYS = "PAYABLE_DAYS";
    public static final String LOP_DAYS = "LOP_DAYS";
    public static final String ENCASH_DAYS = "ENCASH_DAYS";
    public static final String REIMBURSEMENT = "REIMBURSEMENT";
    /** Retro earnings owed for earlier finalized periods. */
    public static final String ARREARS = "ARREARS";
    /** Retro deductions owed for earlier finalized periods (e.g. PF on the arrears). */
    public static final String ARREARS_DEDUCTIONS = "ARREARS_DEDUCTIONS";
    /** Months left in the financial year, this one included (12 in April, 1 in March). */
    public static final String MONTHS_LEFT_IN_FY = "MONTHS_LEFT_IN_FY";
    /** Taxable earnings already paid in finalized runs this financial year. */
    public static final String YTD_TAXABLE = "YTD_TAXABLE";
    public static final String YTD_TDS = "YTD_TDS";

    /** Sum of this payslip's earnings, and of its taxable earnings. Formulas that read these run after them. */
    public static final String GROSS = "GROSS";
    public static final String TAXABLE_GROSS = "TAXABLE_GROSS";

    public static final List<String> PROVIDED = List.of(CTC_ANNUAL, CTC_MONTHLY, PERIOD_DAYS, PAYABLE_DAYS, LOP_DAYS,
            ENCASH_DAYS, REIMBURSEMENT, ARREARS, ARREARS_DEDUCTIONS, MONTHS_LEFT_IN_FY, YTD_TAXABLE, YTD_TDS);

    public static final List<String> AGGREGATES = List.of(GROSS, TAXABLE_GROSS);

    private Inputs() {
    }
}
