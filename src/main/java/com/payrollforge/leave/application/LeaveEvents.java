package com.payrollforge.leave.application;

/** Event types on {@code Outbox.LEAVE}. */
public final class LeaveEvents {

    /** data: leaveId, employeeId, type, from, to, halfDay, days */
    public static final String APPROVED = "LeaveApproved";

    private LeaveEvents() {
    }
}
