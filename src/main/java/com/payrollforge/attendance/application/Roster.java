package com.payrollforge.attendance.application;

import com.payrollforge.org.application.Calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port over employee + org: who works where on a date, and that location's calendar. */
public interface Roster {

    /** The employee's location on {@code day}, if employed that day. */
    Optional<Calendar> calendarFor(UUID employee, LocalDate day);

    /** Everyone employed on {@code day}, with their location's calendar. */
    Map<UUID, Calendar> employedOn(LocalDate day);

    List<Calendar> calendars();
}
