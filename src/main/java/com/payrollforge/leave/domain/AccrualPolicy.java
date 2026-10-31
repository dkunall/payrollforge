package com.payrollforge.leave.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/** Strategy: how many days a leave type credits for a month of service. */
public sealed interface AccrualPolicy {

    /**
     * @param employedFrom join date; a mid-month joiner gets the month pro-rated by calendar days
     * @param employedTo   exit date or null
     */
    BigDecimal creditFor(YearMonth month, LocalDate employedFrom, LocalDate employedTo, BigDecimal currentBalance);

    /** No accrual: the type has no balance to speak of (loss of pay) or is granted by HR. */
    record None() implements AccrualPolicy {
        @Override
        public BigDecimal creditFor(YearMonth m, LocalDate from, LocalDate to, BigDecimal balance) {
            return BigDecimal.ZERO;
        }
    }

    /** {@code perMonth} days a month, pro-rated for partial months, never taking the balance above {@code cap}. */
    record Monthly(BigDecimal perMonth, BigDecimal cap) implements AccrualPolicy {
        @Override
        public BigDecimal creditFor(YearMonth m, LocalDate from, LocalDate to, BigDecimal balance) {
            LocalDate start = from.isAfter(m.atDay(1)) ? from : m.atDay(1);
            LocalDate end = to != null && to.isBefore(m.atEndOfMonth()) ? to : m.atEndOfMonth();
            if (start.isAfter(end)) {
                return BigDecimal.ZERO;
            }
            long days = ChronoUnit.DAYS.between(start, end) + 1;
            BigDecimal credit = perMonth.multiply(BigDecimal.valueOf(days))
                    .divide(BigDecimal.valueOf(m.lengthOfMonth()), 2, RoundingMode.HALF_UP);
            BigDecimal room = cap.subtract(balance).max(BigDecimal.ZERO);
            return credit.min(room).setScale(2, RoundingMode.HALF_UP);
        }
    }
}
