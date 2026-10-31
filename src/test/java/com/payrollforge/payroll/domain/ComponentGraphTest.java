package com.payrollforge.payroll.domain;

import com.payrollforge.payroll.domain.structure.ComponentDef;
import com.payrollforge.payroll.domain.structure.ComponentGraph;
import com.payrollforge.payroll.domain.structure.ComponentKind;
import com.payrollforge.payroll.domain.structure.StructureException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.payrollforge.payroll.domain.structure.ComponentKind.DEDUCTION;
import static com.payrollforge.payroll.domain.structure.ComponentKind.EARNING;
import static com.payrollforge.payroll.domain.structure.ComponentKind.INFO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComponentGraphTest {

    private static ComponentDef c(String code, ComponentKind kind, String formula) {
        return new ComponentDef(code, null, kind, formula, kind == EARNING);
    }

    @Test
    void ordersEveryFormulaAfterWhatItReads() {
        // declared out of order on purpose
        var g = ComponentGraph.build(List.of(
                c("PF", DEDUCTION, "round(min(BASIC, 15000) * 0.12)"),
                c("SPECIAL", EARNING, "CTC_MONTHLY * FACTOR - BASIC - HRA"),
                c("HRA", EARNING, "round(BASIC * 0.5)"),
                c("BASIC", EARNING, "round(CTC_MONTHLY * 0.4 * FACTOR)"),
                c("FACTOR", INFO, "PAYABLE_DAYS / PERIOD_DAYS")));
        var order = g.evaluationOrder();
        assertThat(order.indexOf("FACTOR")).isLessThan(order.indexOf("BASIC"));
        assertThat(order.indexOf("BASIC")).isLessThan(order.indexOf("HRA")).isLessThan(order.indexOf("PF"));
        assertThat(order.indexOf("HRA")).isLessThan(order.indexOf("SPECIAL"));
        assertThat(g.dependsOn().get("SPECIAL")).containsExactlyInAnyOrder("FACTOR", "BASIC", "HRA");
    }

    @Test
    void tiesFollowDeclarationOrderSoTheOrderIsStable() {
        var g = ComponentGraph.build(List.of(c("B", EARNING, "1"), c("A", EARNING, "2"), c("C", EARNING, "3")));
        assertThat(g.evaluationOrder()).containsExactly("B", "A", "C");
    }

    @Test
    void aggregatesRunAfterEveryEarning() {
        var g = ComponentGraph.build(List.of(
                c("ESI", DEDUCTION, "if(GROSS <= 21000, round(GROSS * 0.0075, 2), 0)"),
                c("BASIC", EARNING, "10000"),
                c("HRA", EARNING, "5000")));
        assertThat(g.evaluationOrder()).containsExactly("BASIC", "HRA", "ESI");
    }

    @Test
    void rejectsADirectCycleWithItsPath() {
        assertThatThrownBy(() -> ComponentGraph.build(List.of(
                c("BASIC", EARNING, "HRA * 2"), c("HRA", EARNING, "BASIC / 2"))))
                .isInstanceOf(StructureException.class)
                .hasMessageContaining("BASIC -> HRA -> BASIC")
                .extracting("code").isEqualTo("formula-cycle");
    }

    @Test
    void rejectsALongerCycleAndSelfReference() {
        assertThatThrownBy(() -> ComponentGraph.build(List.of(
                c("X", INFO, "1"), c("A", INFO, "B + X"), c("B", INFO, "C"), c("C", INFO, "A"))))
                .hasMessageContaining("A -> B -> C -> A");
        assertThatThrownBy(() -> ComponentGraph.build(List.of(c("A", INFO, "A + 1"))))
                .hasMessageContaining("A -> A");
    }

    @Test
    void anEarningThatReadsGrossIsACycleThroughTheAggregate() {
        assertThatThrownBy(() -> ComponentGraph.build(List.of(
                c("BASIC", EARNING, "1000"), c("BONUS", EARNING, "GROSS * 0.1"))))
                .hasMessageContaining("BONUS -> GROSS -> BONUS");
    }

    @Test
    void rejectsUnknownVariablesDuplicatesAndReservedNames() {
        assertThatThrownBy(() -> ComponentGraph.build(List.of(c("BASIC", EARNING, "SALARY * 0.4"))))
                .extracting("code").isEqualTo("formula-unknown-variable");
        assertThatThrownBy(() -> ComponentGraph.build(List.of(c("A", EARNING, "1"), c("A", EARNING, "2"))))
                .extracting("code").isEqualTo("component-duplicate");
        assertThatThrownBy(() -> ComponentGraph.build(List.of(c("GROSS", EARNING, "1"))))
                .extracting("code").isEqualTo("component-code");
        assertThatThrownBy(() -> ComponentGraph.build(List.of(c("A", EARNING, "1 +"))))
                .extracting("code").isEqualTo("formula-syntax");
        assertThatThrownBy(() -> new ComponentDef("lower", null, EARNING, "1", false))
                .isInstanceOf(StructureException.class);
        assertThatThrownBy(() -> new ComponentDef("PF", null, DEDUCTION, "1", true))
                .hasMessageContaining("only earnings can be taxable");
    }
}
