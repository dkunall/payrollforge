package com.payrollforge.payroll.domain.run;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * A month's CTC when it changed part-way: each salary segment contributes annual/12 for its share of calendar
 * days. Exact arithmetic throughout, one rounding to paise at the end.
 */
public final class Proration {

    public record SalarySegment(LocalDate from, LocalDate to, BigDecimal ctcAnnual) {
    }

    private Proration() {
    }

    public static BigDecimal ctcMonthly(YearMonth period, List<SalarySegment> segments) {
        BigDecimal periodDays = BigDecimal.valueOf(period.lengthOfMonth());
        BigDecimal numerator = BigDecimal.ZERO;
        for (var s : segments) {
            long days = ChronoUnit.DAYS.between(s.from(), s.to()) + 1;
            numerator = numerator.add(s.ctcAnnual().multiply(BigDecimal.valueOf(days)));
        }
        // sum(ctc * days) / (12 * periodDays): a single division keeps the result independent of segment order
        return numerator.divide(periodDays.multiply(BigDecimal.valueOf(12)), 2, RoundingMode.HALF_UP);
    }
}
