package com.payrollforge.employee.adapter;

import com.payrollforge.employee.application.EmployeeService;
import com.payrollforge.employee.application.EmployeeService.NewEmployee;
import com.payrollforge.employee.application.EmployeeView;
import com.payrollforge.employee.application.NewVersion;
import com.payrollforge.employee.application.Task;
import com.payrollforge.employee.domain.EmployeeVersion;
import com.payrollforge.shared.security.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
class EmployeeController {

    private final EmployeeService employees;

    EmployeeController(EmployeeService employees) {
        this.employees = employees;
    }

    record VersionBody(@NotNull LocalDate effectiveFrom, @NotBlank String grade, @NotBlank String dept,
                       @NotBlank String location, UUID managerId, @NotNull BigDecimal ctcAnnual,
                       @NotBlank String structureCode, @NotBlank String reason) {
        NewVersion toVersion() {
            return new NewVersion(effectiveFrom, grade, dept, location, managerId, ctcAnnual, structureCode, reason);
        }
    }

    record CreateBody(@NotBlank String code, @NotBlank String name, @NotBlank @Email String email, String subject,
                      @NotNull LocalDate joinedOn, @NotBlank String bankAccount, @NotBlank String bankIfsc,
                      @NotBlank String pan, @NotBlank String grade, @NotBlank String dept, @NotBlank String location,
                      UUID managerId, @NotNull BigDecimal ctcAnnual, @NotBlank String structureCode) {
    }

    record ExitBody(@NotNull LocalDate lastDay) {
    }

    @PostMapping("/v1/employees")
    @ResponseStatus(HttpStatus.CREATED)
    EmployeeView create(Caller caller, @Valid @RequestBody CreateBody b) {
        return employees.create(caller, new NewEmployee(b.code(), b.name(), b.email(), b.subject(), b.joinedOn(),
                b.bankAccount(), b.bankIfsc(), b.pan(), new NewVersion(b.joinedOn(), b.grade(), b.dept(), b.location(),
                b.managerId(), b.ctcAnnual(), b.structureCode(), "joining")));
    }

    @PostMapping("/v1/employees/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    EmployeeView version(Caller caller, @PathVariable UUID id, @Valid @RequestBody VersionBody b) {
        return employees.addVersion(caller, id, b.toVersion());
    }

    @GetMapping("/v1/employees/{id}")
    EmployeeView get(Caller caller, @PathVariable UUID id,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return employees.get(caller, id, asOf);
    }

    @GetMapping("/v1/employees/{id}/versions")
    List<EmployeeVersion> history(Caller caller, @PathVariable UUID id) {
        return employees.history(caller, id);
    }

    @GetMapping("/v1/employees")
    List<EmployeeService.DirectoryEntry> directory(Caller caller, @RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "100") int limit,
                                                   @RequestParam(defaultValue = "0") int offset) {
        return employees.directory(status, limit, offset);
    }

    @GetMapping("/v1/org-chart")
    List<EmployeeService.OrgNode> orgChart(Caller caller,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return employees.orgChart(asOf);
    }

    @PostMapping("/v1/employees/{id}/exit")
    EmployeeView exit(Caller caller, @PathVariable UUID id, @Valid @RequestBody ExitBody b) {
        return employees.exit(caller, id, b.lastDay());
    }

    @GetMapping("/v1/employees/{id}/tasks")
    List<Task> tasks(Caller caller, @PathVariable UUID id) {
        return employees.tasks(caller, id);
    }

    @PostMapping("/v1/employees/{id}/tasks/{taskId}:complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void complete(Caller caller, @PathVariable UUID id, @PathVariable UUID taskId) {
        employees.completeTask(caller, id, taskId);
    }
}
