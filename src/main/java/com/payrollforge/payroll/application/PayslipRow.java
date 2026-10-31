package com.payrollforge.payroll.application;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A stored payslip; lines are maps of code, label, kind, amount, taxable. */
public record PayslipRow(UUID runId, YearMonth period, UUID employeeId, String code, String name,
                         List<Map<String, Object>> lines, Map<String, String> inputs, BigDecimal gross,
                         BigDecimal deductions, BigDecimal net, BigDecimal taxable, BigDecimal tds, String pdfKey) {
}
