package com.payrollforge.payroll.application;

import com.payrollforge.payroll.domain.run.RunStatus;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PayrollRun(UUID id, String entity, YearMonth period, RunStatus status, String inputHash,
                         Map<String, Object> taxRules, Integer employeeCount, List<String> warnings, String createdBy,
                         String submittedBy, String approvedBy, String finalizedBy, Instant createdAt,
                         Instant startedAt, Instant computedAt, Instant finalizedAt, Instant paidAt) {
}
