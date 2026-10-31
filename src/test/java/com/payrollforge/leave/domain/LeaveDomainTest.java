package com.payrollforge.leave.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeaveDomainTest {

    static final AccrualPolicy EARNED = new AccrualPolicy.Monthly(new BigDecimal("1.25"), new BigDecimal("30"));

    @Test
    void monthly_accrual_prorates_partial_months_and_respects_the_cap() {
        var may = YearMonth.of(2026, 5);
        assertThat(EARNED.creditFor(may, LocalDate.of(2025, 1, 1), null, BigDecimal.ZERO)).isEqualByComparingTo("1.25");
        // joined on the 17th: 15 of 31 days -> 1.25 * 15 / 31 = 0.6048... -> 0.60
        assertThat(EARNED.creditFor(may, LocalDate.of(2026, 5, 17), null, BigDecimal.ZERO)).isEqualByComparingTo("0.60");
        assertThat(EARNED.creditFor(may, LocalDate.of(2025, 1, 1), null, new BigDecimal("29.5"))).isEqualByComparingTo("0.50");
        assertThat(EARNED.creditFor(may, LocalDate.of(2025, 1, 1), null, new BigDecimal("31"))).isEqualByComparingTo("0");
        assertThat(EARNED.creditFor(may, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 4, 30), BigDecimal.ZERO)).isZero();
        assertThat(new AccrualPolicy.None().creditFor(may, LocalDate.of(2025, 1, 1), null, BigDecimal.ZERO)).isZero();
    }

    @Test
    void working_days_skip_weekly_offs_and_holidays() {
        var days = WorkingDays.between(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10),
                Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), Set.of(LocalDate.of(2026, 5, 1)));
        assertThat(days).hasSize(5); // May 4..8; the 1st is a holiday, 2/3 and 9/10 weekends
        assertThat(WorkingDays.charge(days, false)).isEqualByComparingTo("5");
        assertThat(WorkingDays.charge(days.subList(0, 1), true)).isEqualByComparingTo("0.5");
        assertThatThrownBy(() -> WorkingDays.charge(days, true)).isInstanceOf(IllegalArgumentException.class);
    }
}
