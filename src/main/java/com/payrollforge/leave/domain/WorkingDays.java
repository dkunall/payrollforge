package com.payrollforge.leave.domain;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The days a leave actually consumes: weekly offs and the location's holidays inside the range are free. */
public final class WorkingDays {

    private WorkingDays() {
    }

    public static List<LocalDate> between(LocalDate from, LocalDate to, Set<DayOfWeek> weeklyOff, Set<LocalDate> holidays) {
        var out = new ArrayList<LocalDate>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!weeklyOff.contains(d.getDayOfWeek()) && !holidays.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /** Days charged: 0.5 for a single half day, otherwise one per working day. */
    public static BigDecimal charge(List<LocalDate> days, boolean halfDay) {
        if (halfDay) {
            if (days.size() != 1) {
                throw new IllegalArgumentException("a half day must be a single working day");
            }
            return new BigDecimal("0.5");
        }
        return BigDecimal.valueOf(days.size());
    }
}
