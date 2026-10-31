package com.payrollforge.payroll.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What payroll reads from (and settles in) the people, leave and reimbursement modules. */
public interface PayrollSources {

    record Person(UUID id, String code, String name, LocalDate joinedOn, LocalDate exitedOn) {
    }

    /** One stretch of days with the same salary and structure, already clipped to employment. */
    record PaySegment(LocalDate from, LocalDate to, BigDecimal ctcAnnual, String structureCode) {
    }

    record Item(UUID id, BigDecimal amount) {
    }

    record Payee(UUID employeeId, String code, String name, String account, String ifsc) {
    }

    List<Person> employedBetween(LocalDate from, LocalDate to);

    Map<UUID, List<PaySegment>> segments(Collection<Person> people, LocalDate from, LocalDate to);

    /** Approved, not yet paid reimbursement claims (amount in rupees). */
    List<Item> reimbursements(UUID employee);

    /** Pending leave encashments (amount in days). */
    List<Item> encashments(UUID employee);

    void settle(UUID runId, Collection<UUID> reimbursementIds, Collection<UUID> encashmentIds);

    List<Payee> payees(Collection<UUID> employees);
}
