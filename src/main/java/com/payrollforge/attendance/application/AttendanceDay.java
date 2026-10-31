package com.payrollforge.attendance.application;

import com.payrollforge.attendance.domain.DayStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AttendanceDay(UUID employeeId, LocalDate day, DayStatus status, long workedMinutes, BigDecimal payable,
                            boolean missingPunch, int version, Instant closedAt) {
}
