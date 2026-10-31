package com.payrollforge.employee.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** An employee's versions in date order, answering "as of" and "which versions overlap this period". */
public final class Timeline {

    /** The part of a period one version covers; {@code days} is inclusive of both ends. */
    public record Segment(EmployeeVersion version, LocalDate from, LocalDate to) {
        public long days() {
            return ChronoUnit.DAYS.between(from, to) + 1;
        }
    }

    private final List<EmployeeVersion> versions;

    /** @param rows versions with only {@code effectiveFrom} meaningful; {@code effectiveTo} is recomputed here */
    public Timeline(List<EmployeeVersion> rows) {
        var sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(EmployeeVersion::effectiveFrom));
        var out = new ArrayList<EmployeeVersion>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            var v = sorted.get(i);
            if (i > 0 && sorted.get(i - 1).effectiveFrom().equals(v.effectiveFrom())) {
                throw new IllegalArgumentException("two versions start on " + v.effectiveFrom());
            }
            LocalDate to = i + 1 < sorted.size() ? sorted.get(i + 1).effectiveFrom().minusDays(1) : null;
            out.add(new EmployeeVersion(v.employeeId(), v.effectiveFrom(), to, v.grade(), v.dept(), v.location(),
                    v.managerId(), v.ctcAnnual(), v.structureCode()));
        }
        this.versions = List.copyOf(out);
    }

    public List<EmployeeVersion> versions() {
        return versions;
    }

    public Optional<EmployeeVersion> asOf(LocalDate d) {
        return versions.stream().filter(v -> v.covers(d)).findFirst();
    }

    /** Versions overlapping [from, to], clipped to it. Days before the first version are not covered. */
    public List<Segment> segments(LocalDate from, LocalDate to) {
        var out = new ArrayList<Segment>();
        for (var v : versions) {
            LocalDate s = v.effectiveFrom().isAfter(from) ? v.effectiveFrom() : from;
            LocalDate e = v.effectiveTo() == null || v.effectiveTo().isAfter(to) ? to : v.effectiveTo();
            if (!s.isAfter(e)) {
                out.add(new Segment(v, s, e));
            }
        }
        return out;
    }
}
