package com.payrollforge.payroll.domain.tax;

import java.math.BigDecimal;

/** Income above {@code from} and up to {@code upTo} (null: no upper bound) is taxed at {@code rate} (0.05 = 5%). */
public record Slab(BigDecimal from, BigDecimal upTo, BigDecimal rate) {

    public Slab {
        if (from == null || rate == null || from.signum() < 0 || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0
                || (upTo != null && upTo.compareTo(from) <= 0)) {
            throw new IllegalArgumentException("bad slab " + from + ".." + upTo + " @ " + rate);
        }
    }
}
