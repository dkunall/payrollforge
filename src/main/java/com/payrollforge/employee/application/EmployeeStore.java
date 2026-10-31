package com.payrollforge.employee.application;

import com.payrollforge.employee.domain.EmployeeVersion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeStore {

    /** @return false if the code or subject is taken */
    boolean insert(Employee e, Instant at);

    Optional<Employee> find(UUID id);

    /** Locks the employee row; serialises everything that must not interleave for one employee. */
    Optional<Employee> lock(UUID id);

    Optional<UUID> idForSubject(String subject);

    List<Employee> list(String status, int limit, int offset);

    List<Employee> employedBetween(LocalDate from, LocalDate to);

    /** @return false if a version already starts on that date */
    boolean insertVersion(UUID employee, NewVersion v, String by, Instant at);

    List<EmployeeVersion> versions(UUID employee);

    Map<UUID, List<EmployeeVersion>> versions(Collection<UUID> employees);

    void exit(UUID employee, LocalDate on);

    void addTasks(UUID employee, String kind, List<String> titles);

    List<Task> tasks(UUID employee);

    boolean completeTask(UUID employee, UUID task, String by, Instant at);
}
