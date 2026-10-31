package com.payrollforge.payroll.domain.structure;

/** A salary structure that cannot be compiled: bad code, unknown variable, cycle. */
public class StructureException extends RuntimeException {

    private final String code;

    public StructureException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
