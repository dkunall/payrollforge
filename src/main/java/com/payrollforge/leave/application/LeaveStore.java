package com.payrollforge.leave.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface LeaveStore {

    void ensureBalance(UUID employee, String type);

    /**
     * Compare-and-set debit: succeeds only while the balance covers {@code days}. The CHECK (balance >= 0)
     * constraint is the backstop if this is ever bypassed.
     */
    boolean debit(UUID employee, String type, BigDecimal days);

    void credit(UUID employee, String type, BigDecimal days);

    Optional<BigDecimal> lockBalance(UUID employee, String type);

    Map<String, BigDecimal> balances(UUID employee);

    /** @return false when an entry with the same (employee, type, kind, ref) exists: the accrual already ran */
    boolean ledger(UUID employee, String type, String kind, String ref, BigDecimal days, Instant at);

    boolean overlaps(UUID employee, LocalDate from, LocalDate to);

    void insert(LeaveRequest r);

    Optional<LeaveRequest> lock(UUID id);

    Optional<LeaveRequest> find(UUID id);

    void setStatus(UUID id, String status, Instant at);

    List<LeaveRequest> list(UUID employee, int limit);

    /** Approved leave of these employees covering {@code day}. */
    List<LeaveRequest> approvedOn(Collection<UUID> employees, LocalDate day);

    void insertEncashment(Encashment e, Instant at);

    List<Encashment> pendingEncashments(UUID employee);

    void markEncashmentsPaid(Collection<UUID> ids, UUID runId);
}
