package com.payrollforge.payroll.domain.run;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The monthly CTC to pay against when it changed part-way through the month: annual/12 weighted by the calendar
 * days each salary was in force, over the days the employee was employed. Paying for fewer days than the full
 * month (a joiner, a leaver, loss of pay) is then the structure's job through PAYABLE_DAYS / PERIOD_DAYS, so
 * nothing is pro-rated twice. Exact arithmetic, one rounding to paise at the end.
 */
public final class Proration {

    public record SalarySegment(LocalDate from, LocalDate to, BigDecimal ctcAnnual) {
    }

    private Proration() {
    }

    public static BigDecimal ctcMonthly(YearMonth period, List<SalarySegment> segments) {
        BigDecimal numerator = BigDecimal.ZERO;
        long employed = 0;
        for (var s : segments) {
            long days = ChronoUnit.DAYS.between(s.from(), s.to()) + 1;
            if (s.from().isBefore(period.atDay(1)) || s.to().isAfter(period.atEndOfMonth()) || days <= 0) {
                throw new IllegalArgumentException("segment outside " + period);
            }
            employed += days;
            numerator = numerator.add(s.ctcAnnual().multiply(BigDecimal.valueOf(days)));
        }
        if (employed == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        // sum(ctc * days) / (12 * employedDays): one division keeps the result independent of segment order
        return numerator.divide(BigDecimal.valueOf(12 * employed), 2, RoundingMode.HALF_UP);
    }
}
