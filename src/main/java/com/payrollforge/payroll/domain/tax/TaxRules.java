package com.payrollforge.payroll.domain.tax;

import java.util.Map;

/** The tax tables a run used. Copied into the run snapshot so a later config change cannot alter an old run. */
public record TaxRules(String id, Map<RegimeName, SlabRegime> regimes) {

    public TaxRegime regime(RegimeName name) {
        var r = regimes.get(name);
        if (r == null) {
            throw new IllegalArgumentException("no tax table for regime " + name);
        }
        return r;
    }
}
