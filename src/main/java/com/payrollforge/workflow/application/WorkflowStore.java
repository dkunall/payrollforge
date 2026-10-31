package com.payrollforge.workflow.application;

import com.payrollforge.workflow.domain.Delegation;
import com.payrollforge.workflow.domain.Step;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface WorkflowStore {

    void insertSteps(String subjectType, UUID subjectId, List<Step> steps);

    /** All steps of a subject, row-locked, in order. */
    List<StepRow> lockSteps(String subjectType, UUID subjectId);

    List<StepRow> steps(String subjectType, UUID subjectId);

    void decide(UUID step, String status, String by, UUID onBehalfOf, Instant at);

    void activate(UUID step);

    void skipRemaining(String subjectType, UUID subjectId);

    List<Delegation> delegationsFrom(UUID delegator);

    List<Delegation> delegationsTo(UUID delegate, LocalDate day);

    void insertDelegation(UUID id, Delegation d, Instant at);

    /** Pending steps assigned to any of {@code assignees}, plus pending HR steps when {@code hr}. */
    List<StepRow> pending(List<UUID> assignees, boolean hr, int limit);
}
