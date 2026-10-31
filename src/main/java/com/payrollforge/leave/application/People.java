package com.payrollforge.leave.application;

import com.payrollforge.org.application.Calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port over the employee and org modules. */
public interface People {

    record Person(UUID id, LocalDate joinedOn, LocalDate exitedOn) {
    }

    /** Row-locks the employee, serialising that employee's leave changes. */
    Optional<Person> lock(UUID employee);

    Optional<UUID> managerOn(UUID employee, LocalDate day);

    Optional<Calendar> calendarOn(UUID employee, LocalDate day);

    List<Person> employedBetween(LocalDate from, LocalDate to);
}
