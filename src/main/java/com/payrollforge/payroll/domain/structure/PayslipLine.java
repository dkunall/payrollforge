package com.payrollforge.payroll.domain.structure;

import java.math.BigDecimal;

public record PayslipLine(String code, String label, ComponentKind kind, BigDecimal amount, boolean taxable) {
}
