package com.payrollforge.payroll.domain.tax;

import java.math.BigDecimal;

/** Strategy: annual tax on annual taxable earnings, given what the employee declared. */
public interface TaxRegime {

    RegimeName name();

    BigDecimal annualTax(BigDecimal annualEarnings, BigDecimal declaredDeductions);
}
