package com.payrollforge.reimbursement.application;

import com.payrollforge.shared.security.Caller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Port over workflow and employee: who approves, and recording decisions. */
public interface ClaimApprovals {

    enum Outcome { PENDING, APPROVED, REJECTED }

    Optional<UUID> managerOn(UUID employee, LocalDate day);

    void start(UUID claimId, UUID requester, UUID manager, BigDecimal amount, BigDecimal hrAbove);

    Outcome decide(Caller caller, UUID claimId, UUID requester, boolean approve);
}
