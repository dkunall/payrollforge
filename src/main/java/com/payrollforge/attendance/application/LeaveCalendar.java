package com.payrollforge.attendance.application;

import com.payrollforge.attendance.domain.DayClassifier.LeaveOnDay;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Port over the leave module: approved leave covering a date. */
public interface LeaveCalendar {

    Map<UUID, LeaveOnDay> approvedOn(Collection<UUID> employees, LocalDate day);
}
