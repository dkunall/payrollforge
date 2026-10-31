package com.payrollforge.reimbursement.adapter;

import com.payrollforge.employee.application.EmployeeService;
import com.payrollforge.employee.domain.EmployeeVersion;
import com.payrollforge.reimbursement.application.ClaimApprovals;
import com.payrollforge.shared.security.Caller;
import com.payrollforge.workflow.application.WorkflowService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Component
class WorkflowClaimApprovals implements ClaimApprovals {

    static final String SUBJECT = "REIMBURSEMENT";

    private final WorkflowService workflow;
    private final EmployeeService employees;

    WorkflowClaimApprovals(WorkflowService workflow, EmployeeService employees) {
        this.workflow = workflow;
        this.employees = employees;
    }

    @Override
    public Optional<UUID> managerOn(UUID employee, LocalDate day) {
        return employees.versionOn(employee, day).map(EmployeeVersion::managerId);
    }

    @Override
    public void start(UUID claimId, UUID requester, UUID manager, BigDecimal amount, BigDecimal hrAbove) {
        workflow.start(SUBJECT, claimId, requester, manager, amount, hrAbove);
    }

    @Override
    public Outcome decide(Caller caller, UUID claimId, UUID requester, boolean approve) {
        return Outcome.valueOf(workflow.decide(caller, SUBJECT, claimId, requester, approve).name());
    }
}
