package com.payrollforge.payroll.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface TaxDeclarationStore {

    record Declaration(UUID employeeId, String fy, String regime, Map<String, BigDecimal> items, BigDecimal total,
                       Instant updatedAt) {
    }

    void upsert(Declaration d);

    Optional<Declaration> find(UUID employee, String fy);

    Map<UUID, Declaration> forYear(Collection<UUID> employees, String fy);
}
