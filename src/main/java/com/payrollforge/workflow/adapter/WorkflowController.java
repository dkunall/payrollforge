package com.payrollforge.workflow.adapter;

import com.payrollforge.shared.security.Caller;
import com.payrollforge.workflow.application.StepRow;
import com.payrollforge.workflow.application.WorkflowService;
import com.payrollforge.workflow.domain.Delegation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
class WorkflowController {

    private final WorkflowService workflow;

    WorkflowController(WorkflowService workflow) {
        this.workflow = workflow;
    }

    record DelegationBody(@NotNull UUID delegate, @NotNull LocalDate from, @NotNull LocalDate to) {
    }

    @GetMapping("/v1/approvals/pending")
    List<StepRow> pending(Caller caller) {
        return workflow.pending(caller);
    }

    @PostMapping("/v1/delegations")
    @ResponseStatus(HttpStatus.CREATED)
    Delegation delegate(Caller caller, @Valid @RequestBody DelegationBody b) {
        return workflow.delegate(caller, b.delegate(), b.from(), b.to());
    }
}
