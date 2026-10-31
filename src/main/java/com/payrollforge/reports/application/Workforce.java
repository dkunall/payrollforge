package com.payrollforge.reports.application;

import java.time.LocalDate;
import java.util.List;

/** What reports need to know about people: who was employed, in which department, and when they left. */
public interface Workforce {

    record Member(String code, String dept, LocalDate joinedOn, LocalDate exitedOn) {
    }

    /** Everyone employed for at least one day in the range, with their department as of {@code deptOn}. */
    List<Member> employedBetween(LocalDate from, LocalDate to, LocalDate deptOn);
}
