package com.payrollforge.attendance.application;

/** Event types this module publishes on {@code Outbox.ATTENDANCE}. */
public final class AttendanceEvents {

    /** data: employeeId, day, status, payable, workedMinutes, version. A higher version replaces a lower one. */
    public static final String DAY_CLOSED = "AttendanceDayClosed";

    private AttendanceEvents() {
    }
}
