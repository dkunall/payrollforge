package com.payrollforge.workflow.domain;

import java.time.LocalDate;
import java.util.UUID;

/** {@code delegate} may approve on behalf of {@code delegator} from {@code from} to {@code to} inclusive. */
public record Delegation(UUID delegator, UUID delegate, LocalDate from, LocalDate to) {

    public boolean activeOn(LocalDate d) {
        return !d.isBefore(from) && !d.isAfter(to);
    }
}
