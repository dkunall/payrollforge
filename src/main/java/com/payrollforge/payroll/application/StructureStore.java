package com.payrollforge.payroll.application;

import com.payrollforge.payroll.domain.run.StructureSpec;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface StructureStore {

    record Version(StructureSpec spec, LocalDate effectiveFrom, String createdBy, Instant createdAt) {
    }

    int latestVersion(String code);

    boolean insert(StructureSpec spec, LocalDate effectiveFrom, Map<String, Set<String>> dependsOn, String by, Instant at);

    List<Version> versions(String code);

    List<String> codes();

    /** The newest version whose effective date is on or before the given day. */
    Optional<StructureSpec> effectiveOn(String code, LocalDate day);
}
