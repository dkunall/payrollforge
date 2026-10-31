package com.payrollforge.attendance.adapter;

import com.payrollforge.attendance.application.Roster;
import com.payrollforge.employee.application.EmployeeService;
import com.payrollforge.employee.domain.Timeline;
import com.payrollforge.org.application.Calendar;
import com.payrollforge.org.application.Location;
import com.payrollforge.org.application.OrgService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
class EmployeeRoster implements Roster {

    private final EmployeeService employees;
    private final OrgService org;

    EmployeeRoster(EmployeeService employees, OrgService org) {
        this.employees = employees;
        this.org = org;
    }

    @Override
    public Optional<Calendar> calendarFor(UUID employee, LocalDate day) {
        return employees.find(employee).filter(e -> e.employedOn(day))
                .flatMap(e -> employees.versionOn(employee, day))
                .map(v -> org.calendar(v.location()));
    }

    @Override
    public Map<UUID, Calendar> employedOn(LocalDate day) {
        var people = employees.employedBetween(day, day);
        var versions = employees.versions(people.stream().map(e -> e.id()).toList());
        var out = new HashMap<UUID, Calendar>();
        for (var e : people) {
            new Timeline(versions.getOrDefault(e.id(), List.of())).asOf(day)
                    .ifPresent(v -> out.put(e.id(), org.calendar(v.location())));
        }
        return out;
    }

    @Override
    public List<Calendar> calendars() {
        return org.locations().stream().map(Location::code).map(org::calendar).toList();
    }
}
