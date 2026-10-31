package com.payrollforge.payroll.domain.run;

import java.time.YearMonth;

/** Payroll periods are calendar months; the financial year runs April to March. */
public final class Period {

    private Period() {
    }

    public static int monthsLeftInFy(YearMonth p) {
        int index = (p.getMonthValue() - 4 + 12) % 12; // April = 0
        return 12 - index;
    }

    public static YearMonth fyStart(YearMonth p) {
        return YearMonth.of(p.getMonthValue() >= 4 ? p.getYear() : p.getYear() - 1, 4);
    }

    /** "2026-27" for any month from April 2026 to March 2027. */
    public static String fyLabel(YearMonth p) {
        int y = fyStart(p).getYear();
        return y + "-" + String.format("%02d", (y + 1) % 100);
    }
}
