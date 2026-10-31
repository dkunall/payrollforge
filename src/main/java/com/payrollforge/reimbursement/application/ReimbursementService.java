package com.payrollforge.reimbursement.application;

import com.payrollforge.shared.config.BusinessZone;
import com.payrollforge.shared.config.PayrollForgeProperties;
import com.payrollforge.shared.domain.DomainConflictException;
import com.payrollforge.shared.error.ApiException;
import com.payrollforge.shared.security.Caller;
import com.payrollforge.shared.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Expense claims: manager approves, HR too above a threshold; approved claims are paid by the next payroll. */
@Service
public class ReimbursementService {

    private static final Set<String> CATEGORIES = Set.of("TRAVEL", "INTERNET", "MEALS", "EQUIPMENT", "OTHER");
    private static final BigDecimal MAX = new BigDecimal("200000.00");

    private final ClaimStore store;
    private final ClaimApprovals approvals;
    private final PayrollForgeProperties props;
    private final Clock clock;

    public ReimbursementService(ClaimStore store, ClaimApprovals approvals, PayrollForgeProperties props, Clock clock) {
        this.store = store;
        this.approvals = approvals;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public Claim submit(Caller caller, String category, BigDecimal amount, LocalDate spentOn, String note) {
        UUID me = caller.requireEmployee();
        if (!CATEGORIES.contains(category)) {
            throw ApiException.invalid("category must be one of " + CATEGORIES);
        }
        if (amount.signum() <= 0 || amount.scale() > 2 || amount.compareTo(MAX) > 0) {
            throw ApiException.invalid("amount must be positive, at most 2 decimals and at most " + MAX.toPlainString());
        }
        LocalDate today = BusinessZone.today(clock);
        if (spentOn.isAfter(today) || spentOn.isBefore(today.minusDays(90))) {
            throw ApiException.invalid("spentOn must be within the last 90 days");
        }
        var c = new Claim(UUID.randomUUID(), me, category, amount.setScale(2), spentOn, note, "SUBMITTED", null);
        store.insert(c, clock.instant());
        approvals.start(c.id(), me, approvals.managerOn(me, today).orElse(null), c.amount(),
                props.approvals().claimHrAboveAmount());
        return c;
    }

    @Transactional
    public Claim decide(Caller caller, UUID id, boolean approve) {
        var c = store.lock(id).orElseThrow(() -> ApiException.notFound("reimbursement " + id));
        if (!c.status().equals("SUBMITTED")) {
            throw new DomainConflictException("already-decided", "This claim is already " + c.status());
        }
        switch (approvals.decide(caller, id, c.employeeId(), approve)) {
            case APPROVED -> store.setStatus(id, "APPROVED");
            case REJECTED -> store.setStatus(id, "REJECTED");
            case PENDING -> { }
        }
        return store.find(id).orElseThrow();
    }

    public Claim get(Caller caller, UUID id) {
        var c = store.find(id).orElseThrow(() -> ApiException.notFound("reimbursement " + id));
        caller.requireSelfOr(c.employeeId(), Role.HR, Role.PAYROLL_MAKER, Role.PAYROLL_CHECKER);
        return c;
    }

    public List<Claim> list(Caller caller, UUID employee) {
        caller.requireSelfOr(employee, Role.HR, Role.PAYROLL_MAKER, Role.PAYROLL_CHECKER);
        return store.list(employee);
    }

    public List<Claim> approvedUnpaid(UUID employee) {
        return store.approvedUnpaid(employee);
    }

    @Transactional
    public void markPaid(Collection<UUID> ids, UUID runId) {
        store.markPaid(ids, runId);
    }
}
