package com.payrollforge.workflow.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** What is being approved: who asked, their manager on the relevant date, and its size (days or rupees). */
public record Subject(UUID requester, UUID manager, BigDecimal size) {
}
