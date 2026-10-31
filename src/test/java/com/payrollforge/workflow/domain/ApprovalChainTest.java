package com.payrollforge.workflow.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalChainTest {

    static final UUID EMP = UUID.randomUUID();
    static final UUID MGR = UUID.randomUUID();
    static final UUID DELEGATE = UUID.randomUUID();
    static final LocalDate DAY = LocalDate.of(2026, 5, 4);

    static ApprovalChain chain() {
        return ApprovalChain.manager().then(ApprovalChain.hrAbove(new BigDecimal("3")));
    }

    @Test
    void short_leave_needs_the_manager_long_leave_also_hr() {
        assertThat(chain().plan(new Subject(EMP, MGR, new BigDecimal("2")))).extracting(Step::role)
                .containsExactly(StepRole.MANAGER);
        assertThat(chain().plan(new Subject(EMP, MGR, new BigDecimal("4")))).extracting(Step::role)
                .containsExactly(StepRole.MANAGER, StepRole.HR);
        assertThat(chain().plan(new Subject(EMP, null, new BigDecimal("1")))).extracting(Step::role)
                .containsExactly(StepRole.HR);
    }

    @Test
    void assignee_or_active_delegate_may_act_but_never_the_requester() {
        var s = new Subject(EMP, MGR, BigDecimal.ONE);
        var step = new Step(StepRole.MANAGER, MGR);
        var delegations = List.of(new Delegation(MGR, DELEGATE, DAY, DAY.plusDays(2)),
                new Delegation(MGR, EMP, DAY, DAY.plusDays(2)));
        assertThat(ApprovalChain.mayAct(step, s, new Actor(MGR, false), DAY, delegations)).isTrue();
        assertThat(ApprovalChain.mayAct(step, s, new Actor(DELEGATE, false), DAY, delegations)).isTrue();
        assertThat(ApprovalChain.mayAct(step, s, new Actor(DELEGATE, false), DAY.plusDays(3), delegations)).isFalse();
        assertThat(ApprovalChain.mayAct(step, s, new Actor(EMP, true), DAY, delegations)).isFalse();
        assertThat(ApprovalChain.mayAct(new Step(StepRole.HR, null), s, new Actor(UUID.randomUUID(), true), DAY, List.of())).isTrue();
        assertThat(ApprovalChain.mayAct(new Step(StepRole.HR, null), s, new Actor(MGR, false), DAY, List.of())).isFalse();
    }

    @Test
    void delegation_is_one_hop_only() {
        UUID second = UUID.randomUUID();
        var s = new Subject(EMP, MGR, BigDecimal.ONE);
        var delegations = List.of(new Delegation(MGR, DELEGATE, DAY, DAY), new Delegation(DELEGATE, second, DAY, DAY));
        assertThat(ApprovalChain.mayAct(new Step(StepRole.MANAGER, MGR), s, new Actor(second, false), DAY, delegations)).isFalse();
    }
}
