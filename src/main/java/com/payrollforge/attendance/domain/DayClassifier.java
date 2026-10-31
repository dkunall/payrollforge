package com.payrollforge.attendance.domain;

import java.math.BigDecimal;

/**
 * Decides a closed day's status and how much of it is paid. Order matters: a weekly off or holiday is paid
 * whatever the punches say; an approved leave beats punches; otherwise worked minutes against the shift.
 */
public final class DayClassifier {

    /** An approved leave on the day. {@code halfDay} leaves the other half to be worked. */
    public record LeaveOnDay(boolean paid, boolean halfDay) {
    }

    public record Shift(int fullDayMinutes, int halfDayMinutes) {
        public Shift {
            if (halfDayMinutes <= 0 || fullDayMinutes < halfDayMinutes) {
                throw new IllegalArgumentException("need 0 < half-day <= full-day minutes");
            }
        }
    }

    public record Outcome(DayStatus status, BigDecimal payable) {
    }

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private DayClassifier() {
    }

    public static Outcome classify(boolean weeklyOff, boolean holiday, LeaveOnDay leave, long workedMinutes,
                                   Shift shift) {
        if (holiday) {
            return new Outcome(DayStatus.HOLIDAY, BigDecimal.ONE);
        }
        if (weeklyOff) {
            return new Outcome(DayStatus.WEEKLY_OFF, BigDecimal.ONE);
        }
        if (leave != null) {
            if (!leave.halfDay()) {
                return new Outcome(DayStatus.LEAVE, leave.paid() ? BigDecimal.ONE : BigDecimal.ZERO);
            }
            BigDecimal p = (leave.paid() ? HALF : BigDecimal.ZERO)
                    .add(workedMinutes >= shift.halfDayMinutes() ? HALF : BigDecimal.ZERO);
            return new Outcome(DayStatus.LEAVE, p);
        }
        if (workedMinutes >= shift.fullDayMinutes()) {
            return new Outcome(DayStatus.PRESENT, BigDecimal.ONE);
        }
        if (workedMinutes >= shift.halfDayMinutes()) {
            return new Outcome(DayStatus.HALF_DAY, HALF);
        }
        return new Outcome(DayStatus.ABSENT, BigDecimal.ZERO);
    }
}
