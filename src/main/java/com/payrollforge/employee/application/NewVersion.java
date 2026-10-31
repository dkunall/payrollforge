package com.payrollforge.employee.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** An effective-dated change: everything below is true from {@code effectiveFrom} until the next version. */
public record NewVersion(LocalDate effectiveFrom, String grade, String dept, String location, UUID managerId,
                         BigDecimal ctcAnnual, String structureCode, String reason) {
}
