package com.payrollforge.shared.config;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** The legal entity's zone, used for "today" in as-of queries and for payroll periods. Single entity, so a constant. */
public final class BusinessZone {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private BusinessZone() {
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }
}
