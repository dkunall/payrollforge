package com.payrollforge.workflow.application;

import com.payrollforge.workflow.domain.StepRole;

import java.time.Instant;
import java.util.UUID;

public record StepRow(UUID id, String subjectType, UUID subjectId, int stepNo, StepRole role, UUID assignee,
                      String status, String decidedBy, UUID onBehalfOf, Instant decidedAt, long version) {
}
