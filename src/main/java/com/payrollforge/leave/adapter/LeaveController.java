package com.payrollforge.leave.adapter;

import com.payrollforge.leave.application.Encashment;
import com.payrollforge.leave.application.LeaveRequest;
import com.payrollforge.leave.application.LeaveService;
import com.payrollforge.shared.security.Caller;
import com.payrollforge.workflow.application.StepRow;
import com.payrollforge.workflow.application.WorkflowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
class LeaveController {

    private final LeaveService leave;
    private final WorkflowService workflow;

    LeaveController(LeaveService leave, WorkflowService workflow) {
        this.leave = leave;
        this.workflow = workflow;
    }

    record ApplyBody(@NotBlank String type, @NotNull LocalDate from, @NotNull LocalDate to, boolean halfDay, String reason) {
    }

    record AccrualBody(@NotNull YearMonth month) {
    }

    record EncashBody(@NotBlank String type, @NotNull BigDecimal days) {
    }

    record GrantBody(@NotBlank String type, @NotNull BigDecimal days, @NotBlank String ref) {
    }

    record LeaveView(LeaveRequest leave, List<StepRow> approvals) {
    }

    @PostMapping("/v1/leaves")
    @ResponseStatus(HttpStatus.CREATED)
    LeaveView apply(Caller caller, @Valid @RequestBody ApplyBody b) {
        var r = leave.apply(caller, b.type(), b.from(), b.to(), b.halfDay(), b.reason());
        return new LeaveView(r, workflow.steps(WorkflowApprovals.SUBJECT, r.id()));
    }

    @GetMapping("/v1/leaves/{id}")
    LeaveView get(Caller caller, @PathVariable UUID id) {
        return new LeaveView(leave.get(caller, id), workflow.steps(WorkflowApprovals.SUBJECT, id));
    }

    @PostMapping("/v1/leaves/{id}/approve")
    LeaveView approve(Caller caller, @PathVariable UUID id) {
        return new LeaveView(leave.decide(caller, id, true), workflow.steps(WorkflowApprovals.SUBJECT, id));
    }

    @PostMapping("/v1/leaves/{id}/reject")
    LeaveView reject(Caller caller, @PathVariable UUID id) {
        return new LeaveView(leave.decide(caller, id, false), workflow.steps(WorkflowApprovals.SUBJECT, id));
    }

    @GetMapping("/v1/employees/{id}/leaves")
    List<LeaveRequest> list(Caller caller, @PathVariable UUID id) {
        return leave.list(caller, id);
    }

    @GetMapping("/v1/employees/{id}/leave-balances")
    Map<String, BigDecimal> balances(Caller caller, @PathVariable UUID id) {
        return leave.balances(caller, id);
    }

    @PostMapping("/v1/employees/{id}/leave-grants")
    Map<String, BigDecimal> grant(Caller caller, @PathVariable UUID id, @Valid @RequestBody GrantBody b) {
        leave.grant(caller, id, b.type(), b.days(), b.ref());
        return leave.balances(caller, id);
    }

    @PostMapping("/v1/leave/accruals:run")
    LeaveService.AccrualResult accrue(Caller caller, @Valid @RequestBody AccrualBody b) {
        return leave.accrue(caller, b.month());
    }

    @PostMapping("/v1/leave/encashments")
    @ResponseStatus(HttpStatus.CREATED)
    Encashment encash(Caller caller, @Valid @RequestBody EncashBody b) {
        return leave.encash(caller, b.type(), b.days());
    }
}
