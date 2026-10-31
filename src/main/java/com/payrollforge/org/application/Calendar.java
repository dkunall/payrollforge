package com.payrollforge.org.application;

import java.time.LocalDate;
import java.util.Map;

/** A location with its holidays: what attendance and leave need to decide whether a date is a working day. */
public record Calendar(Location location, Map<LocalDate, String> holidays) {

    public boolean isWeeklyOff(LocalDate d) {
        return location.weeklyOff().contains(d.getDayOfWeek());
    }

    public boolean isHoliday(LocalDate d) {
        return holidays.containsKey(d);
    }
}
