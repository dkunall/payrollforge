package com.payrollforge.workflow.application;

import com.payrollforge.shared.config.BusinessZone;
import com.payrollforge.shared.domain.DomainConflictException;
import com.payrollforge.shared.error.ApiException;
import com.payrollforge.shared.security.Caller;
import com.payrollforge.shared.security.Role;
import com.payrollforge.workflow.domain.Actor;
import com.payrollforge.workflow.domain.ApprovalChain;
import com.payrollforge.workflow.domain.Delegation;
import com.payrollforge.workflow.domain.Step;
import com.payrollforge.workflow.domain.Subject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Multi-step approvals for any subject (leave, reimbursement). The plan is computed once when the subject is
 * created and stored as rows; deciding locks the subject's steps, so two approvers acting at once are serialised
 * and the loser sees the step already decided.
 */
@Service
public class WorkflowService {

    private final WorkflowStore store;
    private final Clock clock;

    public WorkflowService(WorkflowStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Manager first (if any), then HR when {@code size} exceeds {@code hrAbove}. Returns the planned steps. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Step> start(String subjectType, UUID subjectId, UUID requester, UUID manager, BigDecimal size,
                            BigDecimal hrAbove) {
        var chain = ApprovalChain.manager().then(ApprovalChain.hrAbove(hrAbove));
        var steps = chain.plan(new Subject(requester, manager, size));
        store.insertSteps(subjectType, subjectId, steps);
        return steps;
    }

    /**
     * The caller decides the subject's current step. Rejecting ends the chain; approving moves to the next step
     * or finishes it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Decision decide(Caller caller, String subjectType, UUID subjectId, UUID requester, boolean approve) {
        var steps = store.lockSteps(subjectType, subjectId);
        if (steps.isEmpty()) {
            throw ApiException.notFound(subjectType.toLowerCase() + " " + subjectId);
        }
        LocalDate today = BusinessZone.today(clock);
        var actor = new Actor(caller.employeeId(), caller.has(Role.HR));
        var subject = new Subject(requester, null, BigDecimal.ZERO);
        StepRow current = steps.stream().filter(s -> s.status().equals("PENDING")).findFirst().orElse(null);
        if (current == null) {
            throw new DomainConflictException("already-decided", "This request has already been decided");
        }
        var step = new Step(current.role(), current.assignee());
        List<Delegation> delegations = current.assignee() == null ? List.of() : store.delegationsFrom(current.assignee());
        if (!ApprovalChain.mayAct(step, subject, actor, today, delegations)) {
            boolean decidedEarlier = steps.stream().anyMatch(s -> !s.status().equals("PENDING")
                    && !s.status().equals("WAITING") && caller.subject().equals(s.decidedBy()));
            if (decidedEarlier) {
                throw new DomainConflictException("already-decided", "You already decided your step of this request");
            }
            throw ApiException.forbidden("not-your-step", "You are not the approver of the current step");
        }
        UUID onBehalf = actor.employeeId() != null && current.assignee() != null
                && !actor.employeeId().equals(current.assignee()) ? current.assignee() : null;
        store.decide(current.id(), approve ? "APPROVED" : "REJECTED", caller.subject(), onBehalf, clock.instant());
        if (!approve) {
            store.skipRemaining(subjectType, subjectId);
            return Decision.REJECTED;
        }
        var next = steps.stream().filter(s -> s.stepNo() > current.stepNo() && s.status().equals("WAITING")).findFirst();
        if (next.isPresent()) {
            store.activate(next.get().id());
            return Decision.PENDING;
        }
        return Decision.APPROVED;
    }

    public List<StepRow> steps(String subjectType, UUID subjectId) {
        return store.steps(subjectType, subjectId);
    }

    /** Steps waiting on the caller: their own, delegated to them today, and HR steps for HR. */
    public List<StepRow> pending(Caller caller) {
        var assignees = new ArrayList<UUID>();
        if (caller.employeeId() != null) {
            assignees.add(caller.employeeId());
            store.delegationsTo(caller.employeeId(), BusinessZone.today(clock)).forEach(d -> assignees.add(d.delegator()));
        }
        return store.pending(assignees, caller.has(Role.HR), 200);
    }

    @Transactional
    public Delegation delegate(Caller caller, UUID delegate, LocalDate from, LocalDate to) {
        UUID me = caller.requireEmployee();
        if (me.equals(delegate)) {
            throw ApiException.invalid("you cannot delegate to yourself");
        }
        if (to.isBefore(from) || from.isBefore(BusinessZone.today(clock)) || from.plusDays(90).isBefore(to)) {
            throw ApiException.invalid("a delegation runs from today or later, for at most 90 days");
        }
        var d = new Delegation(me, delegate, from, to);
        store.insertDelegation(UUID.randomUUID(), d, clock.instant());
        return d;
    }
}
