package com.payrollforge.outbox.application;

import java.util.Map;

/**
 * Port for events that must leave the process exactly when the state change commits. Implementations write to
 * the outbox table in the caller's transaction; a relay publishes them afterwards.
 */
public interface Outbox {

    /** AttendanceDayClosed, keyed by employee id so one employee's days stay ordered on one partition. */
    String ATTENDANCE = "payrollforge.attendance";

    /** LeaveApproved, keyed by employee id. */
    String LEAVE = "payrollforge.leave";

    /** PayrollFinalized, PayrollPaid, keyed by run id. */
    String PAYROLL = "payrollforge.payroll";

    void append(String topic, String key, String type, Map<String, Object> data);
}
