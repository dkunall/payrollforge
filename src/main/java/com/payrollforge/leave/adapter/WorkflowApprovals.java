package com.payrollforge.leave.adapter;

import com.payrollforge.leave.application.Approvals;
import com.payrollforge.shared.security.Caller;
import com.payrollforge.workflow.application.WorkflowService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
class WorkflowApprovals implements Approvals {

    static final String SUBJECT = "LEAVE";

    private final WorkflowService workflow;

    WorkflowApprovals(WorkflowService workflow) {
        this.workflow = workflow;
    }

    @Override
    public void start(UUID leaveId, UUID requester, UUID manager, BigDecimal days, BigDecimal hrAbove) {
        workflow.start(SUBJECT, leaveId, requester, manager, days, hrAbove);
    }

    @Override
    public Outcome decide(Caller caller, UUID leaveId, UUID requester, boolean approve) {
        return Outcome.valueOf(workflow.decide(caller, SUBJECT, leaveId, requester, approve).name());
    }
}
