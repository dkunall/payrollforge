package com.payrollforge.payroll.domain.formula;

/** A formula that does not parse, refers to something it may not, or cannot be evaluated. */
public class FormulaException extends RuntimeException {

    private final String code;

    public FormulaException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Stable machine-readable reason, e.g. {@code formula-syntax}, {@code formula-cycle}. */
    public String code() {
        return code;
    }
}
