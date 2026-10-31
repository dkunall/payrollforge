package com.payrollforge.attendance.application;

import com.payrollforge.attendance.domain.PunchSource;

import java.time.Instant;
import java.util.UUID;

/** One punch as submitted. {@code deviceEventId} is the client's id for it and makes resubmission harmless. */
public record PunchIn(String deviceEventId, UUID employeeId, Instant at, PunchSource source, Double lat, Double lng) {
}
