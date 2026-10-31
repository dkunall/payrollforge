package com.payrollforge.payroll.domain.structure;

import java.util.regex.Pattern;

/** One salary component as written by HR: a code, what it is, and how to compute it. */
public record ComponentDef(String code, String label, ComponentKind kind, String formula, boolean taxable) {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    public ComponentDef {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new StructureException("component-code", "component code must match " + CODE + ": " + code);
        }
        if (kind == null) {
            throw new StructureException("component-kind", code + ": kind is required");
        }
        if (taxable && kind != ComponentKind.EARNING) {
            throw new StructureException("component-taxable", code + ": only earnings can be taxable");
        }
        label = label == null || label.isBlank() ? code : label.strip();
    }
}
