package com.payrollforge.leave.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LeaveRequest(UUID id, UUID employeeId, String type, LocalDate from, LocalDate to, boolean halfDay,
                           BigDecimal days, String reason, String status, Instant createdAt, Instant decidedAt) {
}
