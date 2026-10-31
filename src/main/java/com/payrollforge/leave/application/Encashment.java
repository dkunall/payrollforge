package com.payrollforge.leave.application;

import java.math.BigDecimal;
import java.util.UUID;

public record Encashment(UUID id, UUID employeeId, String type, BigDecimal days, String status, UUID runId) {
}
