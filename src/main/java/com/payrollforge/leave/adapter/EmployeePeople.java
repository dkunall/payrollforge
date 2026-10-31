package com.payrollforge.leave.adapter;

import com.payrollforge.employee.application.Employee;
import com.payrollforge.employee.application.EmployeeService;
import com.payrollforge.employee.domain.EmployeeVersion;
import com.payrollforge.leave.application.People;
import com.payrollforge.org.application.Calendar;
import com.payrollforge.org.application.OrgService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class EmployeePeople implements People {

    private final EmployeeService employees;
    private final OrgService org;

    EmployeePeople(EmployeeService employees, OrgService org) {
        this.employees = employees;
        this.org = org;
    }

    @Override
    public Optional<Person> lock(UUID employee) {
        return employees.lock(employee).map(EmployeePeople::person);
    }

    @Override
    public Optional<UUID> managerOn(UUID employee, LocalDate day) {
        return employees.versionOn(employee, day).map(EmployeeVersion::managerId);
    }

    @Override
    public Optional<Calendar> calendarOn(UUID employee, LocalDate day) {
        return employees.versionOn(employee, day).map(v -> org.calendar(v.location()));
    }

    @Override
    public List<Person> employedBetween(LocalDate from, LocalDate to) {
        return employees.employedBetween(from, to).stream().map(EmployeePeople::person).toList();
    }

    private static Person person(Employee e) {
        return new Person(e.id(), e.joinedOn(), e.exitedOn());
    }
}
