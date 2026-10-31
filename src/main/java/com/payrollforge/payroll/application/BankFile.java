package com.payrollforge.payroll.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BankFile(UUID id, UUID runId, String batchRef, String status, String objectKey, String sha256,
                       BigDecimal total, int rows, String createdBy, String approvedBy, Instant createdAt) {
}
