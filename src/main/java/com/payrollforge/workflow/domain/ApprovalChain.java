package com.payrollforge.workflow.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chain of Responsibility. Each handler looks at the subject, adds its step if it applies, and passes the subject
 * on. The resulting plan is stored with the request, so changing the rules later does not change requests
 * already in flight.
 */
public abstract class ApprovalChain {

    private ApprovalChain next;

    public ApprovalChain then(ApprovalChain next) {
        ApprovalChain tail = this;
        while (tail.next != null) {
            tail = tail.next;
        }
        tail.next = next;
        return this;
    }

    public final List<Step> plan(Subject s) {
        var steps = new ArrayList<Step>();
        for (ApprovalChain h = this; h != null; h = h.next) {
            h.contribute(s, steps);
        }
        if (steps.isEmpty()) {
            steps.add(new Step(StepRole.HR, null)); // somebody always has to approve
        }
        return List.copyOf(steps);
    }

    protected abstract void contribute(Subject s, List<Step> steps);

    /** The requester's manager approves; a requester without a manager goes straight to the next handler. */
    public static ApprovalChain manager() {
        return new ApprovalChain() {
            @Override
            protected void contribute(Subject s, List<Step> steps) {
                if (s.manager() != null && !s.manager().equals(s.requester())) {
                    steps.add(new Step(StepRole.MANAGER, s.manager()));
                }
            }
        };
    }

    /** HR approves when the subject is larger than {@code threshold}, or when nobody else would. */
    public static ApprovalChain hrAbove(BigDecimal threshold) {
        return new ApprovalChain() {
            @Override
            protected void contribute(Subject s, List<Step> steps) {
                if (s.size().compareTo(threshold) > 0 || steps.isEmpty()) {
                    steps.add(new Step(StepRole.HR, null));
                }
            }
        };
    }

    /**
     * May {@code actor} decide {@code step} on {@code day}? The assignee can, and so can someone the assignee
     * delegated to for that day (one hop: a delegate's own delegation does not pass on). HR steps need HR. The
     * requester can never approve their own request, even as somebody's delegate.
     */
    public static boolean mayAct(Step step, Subject s, Actor actor, LocalDate day, List<Delegation> delegations) {
        if (actor.employeeId() != null && actor.employeeId().equals(s.requester())) {
            return false;
        }
        if (step.role() == StepRole.HR) {
            return actor.hr();
        }
        if (actor.employeeId() == null) {
            return false;
        }
        if (actor.employeeId().equals(step.assignee())) {
            return true;
        }
        return delegationFor(step, actor, day, delegations).isPresent();
    }

    public static Optional<Delegation> delegationFor(Step step, Actor actor, LocalDate day, List<Delegation> delegations) {
        return delegations.stream().filter(d -> d.delegator().equals(step.assignee())
                && d.delegate().equals(actor.employeeId()) && d.activeOn(day)).findFirst();
    }
}
