package com.payrollforge.payroll.domain.run;

import java.util.Map;
import java.util.Set;

/**
 * The run's state machine. Each state lists the events it accepts and where they lead; anything else is a
 * conflict. FINALIZED and PAID accept no edits: corrections are arrears in a later run.
 */
public enum RunStatus {
    DRAFT(Map.of(RunEvent.START, "COMPUTING", RunEvent.CANCEL, "CANCELLED")),
    COMPUTING(Map.of(RunEvent.CHUNKS_DONE, "REVIEW", RunEvent.START, "COMPUTING")),
    REVIEW(Map.of(RunEvent.INPUTS_CHANGED, "DRAFT", RunEvent.APPROVE, "APPROVED", RunEvent.CANCEL, "CANCELLED")),
    APPROVED(Map.of(RunEvent.INPUTS_CHANGED, "DRAFT", RunEvent.FINALIZE, "FINALIZED")),
    FINALIZED(Map.of(RunEvent.PAY, "PAID")),
    PAID(Map.of()),
    CANCELLED(Map.of());

    private final Map<RunEvent, String> next;

    RunStatus(Map<RunEvent, String> next) {
        this.next = next;
    }

    public RunStatus on(RunEvent e) {
        String to = next.get(e);
        if (to == null) {
            throw new IllegalRunTransition(this, e);
        }
        return valueOf(to);
    }

    public boolean accepts(RunEvent e) {
        return next.containsKey(e);
    }

    /** Statuses that lock the period: attendance and leave in it can no longer change. */
    public static final Set<RunStatus> LOCKING = Set.of(APPROVED, FINALIZED, PAID);

    /** Statuses whose payslips count as paid for YTD and arrears baselines. */
    public static final Set<RunStatus> SETTLED = Set.of(FINALIZED, PAID);

    public static class IllegalRunTransition extends RuntimeException {
        public IllegalRunTransition(RunStatus from, RunEvent e) {
            super("a " + from + " run cannot " + e.name().toLowerCase().replace('_', ' '));
        }
    }
}
