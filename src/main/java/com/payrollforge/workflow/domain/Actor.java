package com.payrollforge.workflow.domain;

import java.util.UUID;

/** Someone trying to act on a step: their employee id (null for a non-employee account) and whether they are HR. */
public record Actor(UUID employeeId, boolean hr) {
}
