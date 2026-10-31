package com.payrollforge.reports.adapter;

import com.payrollforge.employee.application.EmployeeService;
import com.payrollforge.reports.application.Workforce;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
class EmployeeWorkforce implements Workforce {

    private final EmployeeService employees;

    EmployeeWorkforce(EmployeeService employees) {
        this.employees = employees;
    }

    @Override
    public List<Member> employedBetween(LocalDate from, LocalDate to, LocalDate deptOn) {
        var people = employees.employedBetween(from, to);
        var versions = employees.versions(people.stream().map(e -> e.id()).toList());
        var out = new ArrayList<Member>();
        for (var e : people) {
            LocalDate on = e.exitedOn() != null && e.exitedOn().isBefore(deptOn) ? e.exitedOn() : deptOn;
            String dept = versions.getOrDefault(e.id(), List.of()).stream().filter(v -> v.covers(on))
                    .map(v -> v.dept()).findFirst().orElse("UNASSIGNED");
            out.add(new Member(e.code(), dept, e.joinedOn(), e.exitedOn()));
        }
        return out;
    }
}
