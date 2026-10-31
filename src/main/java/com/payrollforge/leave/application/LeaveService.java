package com.payrollforge.leave.application;

import com.payrollforge.leave.domain.LeaveType;
import com.payrollforge.leave.domain.WorkingDays;
import com.payrollforge.outbox.application.Outbox;
import com.payrollforge.shared.config.PayrollForgeProperties;
import com.payrollforge.shared.domain.DomainConflictException;
import com.payrollforge.shared.domain.DomainRuleException;
import com.payrollforge.shared.error.ApiException;
import com.payrollforge.shared.lock.PeriodLocks;
import com.payrollforge.shared.security.Caller;
import com.payrollforge.shared.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Leave requests and balances. Days are reserved (debited) when a request is made, so two requests cannot both
 * spend the same balance; a rejection refunds them. Every balance change is a ledger row.
 */
@Service
public class LeaveService {

    private final LeaveStore store;
    private final People people;
    private final Approvals approvals;
    private final PeriodLocks locks;
    private final Outbox outbox;
    private final PayrollForgeProperties props;
    private final Clock clock;

    public LeaveService(LeaveStore store, People people, Approvals approvals, PeriodLocks locks, Outbox outbox,
                        PayrollForgeProperties props, Clock clock) {
        this.store = store;
        this.people = people;
        this.approvals = approvals;
        this.locks = locks;
        this.outbox = outbox;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public LeaveRequest apply(Caller caller, String typeCode, LocalDate from, LocalDate to, boolean halfDay, String reason) {
        UUID me = caller.requireEmployee();
        LeaveType type = LeaveTypes.of(typeCode);
        if (to.isBefore(from) || from.plusDays(60).isBefore(to)) {
            throw ApiException.invalid("from..to must be a range of at most 61 days");
        }
        var person = people.lock(me).orElseThrow();
        if (from.isBefore(person.joinedOn()) || (person.exitedOn() != null && to.isAfter(person.exitedOn()))) {
            throw new DomainRuleException("outside-employment", "Leave must fall within your employment");
        }
        locks.requireOpen(from, to);
        if (store.overlaps(me, from, to)) {
            throw new DomainConflictException("leave-overlap", "You already have a pending or approved leave on these dates");
        }
        var cal = people.calendarOn(me, from).orElseThrow();
        var days = WorkingDays.between(from, to, cal.location().weeklyOff(), cal.holidays().keySet());
        if (days.isEmpty()) {
            throw new DomainRuleException("no-working-days", "Every day in this range is a weekly off or holiday");
        }
        BigDecimal charge;
        try {
            charge = WorkingDays.charge(days, halfDay);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(e.getMessage());
        }
        var request = new LeaveRequest(UUID.randomUUID(), me, type.code(), from, to, halfDay, charge, reason,
                "PENDING", clock.instant(), null);
        if (type.limited()) {
            store.ensureBalance(me, type.code());
            if (!store.debit(me, type.code(), charge)) {
                throw new DomainConflictException("insufficient-balance",
                        "Not enough " + type.code() + " balance for " + charge.toPlainString() + " days");
            }
            store.ledger(me, type.code(), "DEBIT", request.id().toString(), charge.negate(), clock.instant());
        }
        store.insert(request);
        approvals.start(request.id(), me, people.managerOn(me, from).orElse(null), charge,
                props.approvals().leaveHrAboveDays());
        return request;
    }

    @Transactional
    public LeaveRequest decide(Caller caller, UUID id, boolean approve) {
        var r = store.lock(id).orElseThrow(() -> ApiException.notFound("leave " + id));
        if (!r.status().equals("PENDING")) {
            throw new DomainConflictException("already-decided", "This leave is already " + r.status());
        }
        if (approve) {
            // the days would change an approved payroll's inputs
            locks.requireOpen(r.from(), r.to());
        }
        var outcome = approvals.decide(caller, id, r.employeeId(), approve);
        switch (outcome) {
            case PENDING -> {
                return r;
            }
            case APPROVED -> {
                store.setStatus(id, "APPROVED", clock.instant());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("leaveId", id.toString());
                data.put("employeeId", r.employeeId().toString());
                data.put("type", r.type());
                data.put("from", r.from().toString());
                data.put("to", r.to().toString());
                data.put("halfDay", r.halfDay());
                data.put("days", r.days().toPlainString());
                outbox.append(Outbox.LEAVE, r.employeeId().toString(), LeaveEvents.APPROVED, data);
            }
            case REJECTED -> {
                store.setStatus(id, "REJECTED", clock.instant());
                if (LeaveTypes.of(r.type()).limited()) {
                    store.credit(r.employeeId(), r.type(), r.days());
                    store.ledger(r.employeeId(), r.type(), "REFUND", id.toString(), r.days(), clock.instant());
                }
            }
        }
        return store.find(id).orElseThrow();
    }

    public LeaveRequest get(Caller caller, UUID id) {
        var r = store.find(id).orElseThrow(() -> ApiException.notFound("leave " + id));
        caller.requireSelfOr(r.employeeId(), Role.HR);
        return r;
    }

    public List<LeaveRequest> list(Caller caller, UUID employee) {
        caller.requireSelfOr(employee, Role.HR);
        return store.list(employee, 200);
    }

    public Map<String, BigDecimal> balances(Caller caller, UUID employee) {
        caller.requireSelfOr(employee, Role.HR, Role.PAYROLL_MAKER, Role.PAYROLL_CHECKER);
        var out = new LinkedHashMap<String, BigDecimal>();
        LeaveTypes.ALL.stream().filter(LeaveType::limited).forEach(t -> out.put(t.code(), new BigDecimal("0.00")));
        out.putAll(store.balances(employee));
        return out;
    }

    public record AccrualResult(YearMonth month, int employees, int credited, BigDecimal days) {
    }

    /**
     * Credit {@code month}'s accrual to everyone employed in it. Safe to run any number of times: the ledger row
     * (employee, type, ACCRUAL, month) is unique, and the balance only moves when that row is new.
     */
    @Transactional
    public AccrualResult accrue(Caller caller, YearMonth month) {
        caller.require(Role.HR);
        if (month.isAfter(YearMonth.now(clock))) {
            throw ApiException.invalid("cannot accrue a future month");
        }
        var staff = people.employedBetween(month.atDay(1), month.atEndOfMonth());
        int credited = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (var p : staff) {
            for (LeaveType t : LeaveTypes.ALL) {
                if (!t.limited()) {
                    continue;
                }
                store.ensureBalance(p.id(), t.code());
                BigDecimal balance = store.lockBalance(p.id(), t.code()).orElseThrow();
                BigDecimal credit = t.accrual().creditFor(month, p.joinedOn(), p.exitedOn(), balance);
                if (store.ledger(p.id(), t.code(), "ACCRUAL", month.toString(), credit, clock.instant())
                        && credit.signum() > 0) {
                    store.credit(p.id(), t.code(), credit);
                    credited++;
                    total = total.add(credit);
                }
            }
        }
        return new AccrualResult(month, staff.size(), credited, total);
    }

    /** Turn encashable leave into pay: the days leave the balance now and are paid by the next payroll run. */
    @Transactional
    public Encashment encash(Caller caller, String typeCode, BigDecimal days) {
        UUID me = caller.requireEmployee();
        LeaveType type = LeaveTypes.of(typeCode);
        if (!type.encashable()) {
            throw new DomainRuleException("not-encashable", type.code() + " cannot be encashed");
        }
        if (days.signum() <= 0 || days.scale() > 1 || days.remainder(new BigDecimal("0.5")).signum() != 0) {
            throw ApiException.invalid("days must be a positive multiple of 0.5");
        }
        people.lock(me).orElseThrow();
        store.ensureBalance(me, type.code());
        if (!store.debit(me, type.code(), days)) {
            throw new DomainConflictException("insufficient-balance", "Not enough " + type.code() + " balance");
        }
        var e = new Encashment(UUID.randomUUID(), me, type.code(), days, "PENDING", null);
        store.ledger(me, type.code(), "ENCASH", e.id().toString(), days.negate(), clock.instant());
        store.insertEncashment(e, clock.instant());
        return e;
    }

    @Transactional
    public BigDecimal grant(Caller caller, UUID employee, String typeCode, BigDecimal days, String ref) {
        caller.require(Role.HR);
        LeaveType type = LeaveTypes.of(typeCode);
        if (!type.limited() || days.signum() <= 0) {
            throw ApiException.invalid("grant positive days of a limited type");
        }
        people.lock(employee).orElseThrow(() -> ApiException.notFound("employee " + employee));
        store.ensureBalance(employee, type.code());
        if (store.ledger(employee, type.code(), "GRANT", ref, days, clock.instant())) {
            store.credit(employee, type.code(), days);
        }
        return store.balances(employee).get(type.code());
    }

    // ---- for other modules -------------------------------------------------------------------------------------

    public record DayLeave(boolean paid, boolean halfDay) {
    }

    public Map<UUID, DayLeave> approvedOn(Collection<UUID> employees, LocalDate day) {
        var out = new HashMap<UUID, DayLeave>();
        for (var r : store.approvedOn(employees, day)) {
            out.put(r.employeeId(), new DayLeave(LeaveTypes.of(r.type()).paid(), r.halfDay()));
        }
        return out;
    }

    public List<Encashment> pendingEncashments(UUID employee) {
        return store.pendingEncashments(employee);
    }

    @Transactional
    public void markEncashmentsPaid(Collection<UUID> ids, UUID runId) {
        store.markEncashmentsPaid(ids, runId);
    }
}
