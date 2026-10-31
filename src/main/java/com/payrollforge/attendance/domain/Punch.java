package com.payrollforge.attendance.domain;

import java.time.Instant;

public record Punch(Instant at, PunchSource source) {
}
