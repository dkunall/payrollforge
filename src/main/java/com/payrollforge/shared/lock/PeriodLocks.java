package com.payrollforge.shared.lock;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * The payroll lock. Once a run is approved its period is locked: attendance and leave for those dates can no
 * longer change, which is what keeps an approved run's inputs still. Owned here so attendance and leave can check
 * it without depending on the payroll module.
 */
public interface PeriodLocks {

    boolean isLocked(YearMonth period);

    default boolean anyLocked(LocalDate from, LocalDate to) {
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            if (isLocked(m)) {
                return true;
            }
        }
        return false;
    }

    /** Throws 409 payroll-locked if any day in [from, to] is in a locked period. */
    void requireOpen(LocalDate from, LocalDate to);

    void lock(YearMonth period, UUID runId);
}
