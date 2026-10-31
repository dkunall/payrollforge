package com.payrollforge.employee.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What was true about an employee from {@code effectiveFrom} until the next version starts. Versions are only
 * ever appended; {@code effectiveTo} (inclusive, null when open) is derived from the next version, never stored
 * by an update.
 */
public record EmployeeVersion(UUID employeeId, LocalDate effectiveFrom, LocalDate effectiveTo, String grade,
                              String dept, String location, UUID managerId, BigDecimal ctcAnnual,
                              String structureCode) {

    public boolean covers(LocalDate d) {
        return !d.isBefore(effectiveFrom) && (effectiveTo == null || !d.isAfter(effectiveTo));
    }
}
