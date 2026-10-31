package com.payrollforge.workflow.domain;

import java.util.UUID;

/** One planned approval. {@code assignee} is the manager's employee id; null for an HR step (any HR user). */
public record Step(StepRole role, UUID assignee) {
}
