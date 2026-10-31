package com.payrollforge.reimbursement.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record Claim(UUID id, UUID employeeId, String category, BigDecimal amount, LocalDate spentOn, String note,
                    String status, UUID runId) {
}
