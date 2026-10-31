package com.payrollforge.leave.application;

import com.payrollforge.shared.security.Caller;

import java.math.BigDecimal;
import java.util.UUID;

/** Port over the workflow module. */
public interface Approvals {

    enum Outcome { PENDING, APPROVED, REJECTED }

    void start(UUID leaveId, UUID requester, UUID manager, BigDecimal days, BigDecimal hrAbove);

    Outcome decide(Caller caller, UUID leaveId, UUID requester, boolean approve);
}
