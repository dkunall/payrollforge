package com.payrollforge.payroll.domain.structure;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** One employee's computed payslip: the lines in evaluation order and every intermediate value. */
public record Computation(List<PayslipLine> lines, BigDecimal gross, BigDecimal deductions, BigDecimal net,
                          Map<String, BigDecimal> values) {

    public BigDecimal amount(String code) {
        return lines.stream().filter(l -> l.code().equals(code)).map(PayslipLine::amount).findFirst()
                .orElse(BigDecimal.ZERO.setScale(2));
    }
}
