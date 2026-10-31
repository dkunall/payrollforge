package com.payrollforge.payroll.domain.run;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunDomainTest {

    @Test
    void state_machine_allows_only_the_documented_path() {
        RunStatus s = RunStatus.DRAFT.on(RunEvent.START).on(RunEvent.CHUNKS_DONE);
        assertThat(s).isEqualTo(RunStatus.REVIEW);
        assertThat(s.on(RunEvent.INPUTS_CHANGED)).isEqualTo(RunStatus.DRAFT);
        RunStatus paid = s.on(RunEvent.APPROVE).on(RunEvent.FINALIZE).on(RunEvent.PAY);
        assertThat(paid).isEqualTo(RunStatus.PAID);
        assertThatThrownBy(() -> RunStatus.FINALIZED.on(RunEvent.START)).isInstanceOf(RunStatus.IllegalRunTransition.class);
        assertThatThrownBy(() -> RunStatus.FINALIZED.on(RunEvent.CANCEL)).isInstanceOf(RunStatus.IllegalRunTransition.class);
        assertThatThrownBy(() -> RunStatus.DRAFT.on(RunEvent.APPROVE)).hasMessageContaining("cannot approve");
        assertThatThrownBy(() -> RunStatus.PAID.on(RunEvent.PAY)).isInstanceOf(RunStatus.IllegalRunTransition.class);
    }

    @Test
    void financial_year_helpers() {
        assertThat(Period.monthsLeftInFy(YearMonth.of(2026, 4))).isEqualTo(12);
        assertThat(Period.monthsLeftInFy(YearMonth.of(2026, 10))).isEqualTo(6);
        assertThat(Period.monthsLeftInFy(YearMonth.of(2027, 3))).isEqualTo(1);
        assertThat(Period.fyStart(YearMonth.of(2027, 2))).isEqualTo(YearMonth.of(2026, 4));
        assertThat(Period.fyLabel(YearMonth.of(2027, 2))).isEqualTo("2026-27");
    }

    @Test
    void canonical_json_is_order_independent_and_escapes() {
        var a = new HashMap<String, Object>();
        a.put("b", List.of("x", true));
        a.put("a", new BigDecimal("1.50"));
        a.put("c", "q\"\\\n");
        var b = new java.util.TreeMap<String, Object>(java.util.Comparator.reverseOrder());
        b.putAll(a);
        assertThat(Canonical.json(a)).isEqualTo("{\"a\":\"1.50\",\"b\":[\"x\",true],\"c\":\"q\\\"\\\\\\u000a\"}");
        assertThat(Canonical.sha256(a)).isEqualTo(Canonical.sha256(b)).hasSize(64);
    }

    @Test
    void snapshot_round_trips_through_maps_with_the_same_hash() {
        var s = new EmployeeSnapshot(UUID.randomUUID(), "E7", "Asha Example", "2026-05", Fixtures.STD,
                Map.of("CTC_ANNUAL", "1200000", "LOP_DAYS", "0.5"), "NEW", "0",
                List.of(new ArrearsItem("2026-04", Fixtures.STD, Map.of("CTC_ANNUAL", "1"), Map.of("BASIC", "40000.00"))),
                List.of("r1"), List.of());
        var back = EmployeeSnapshot.fromMap(s.toMap());
        assertThat(back).isEqualTo(s);
        assertThat(back.hash()).isEqualTo(s.hash());
        var changed = new EmployeeSnapshot(s.employeeId(), s.code(), s.name(), s.period(), s.structure(),
                Map.of("CTC_ANNUAL", "1200000", "LOP_DAYS", "1"), "NEW", "0", s.arrears(), List.of("r1"), List.of());
        assertThat(changed.hash()).isNotEqualTo(s.hash());
        assertThat(EmployeeSnapshot.runHash(Map.of("id", "t"), Map.of(s.employeeId(), s.hash())))
                .isNotEqualTo(EmployeeSnapshot.runHash(Map.of("id", "t2"), Map.of(s.employeeId(), s.hash())));
    }

    @Test
    void proration_weights_each_segment_by_calendar_days() {
        var may = YearMonth.of(2026, 5);
        // 9 days at 12 L then 22 days at 15 L: (12L*9 + 15L*22) / (12*31) = 117741.935... -> 117741.94
        var m = Proration.ctcMonthly(may, List.of(
                new Proration.SalarySegment(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 9), new BigDecimal("1200000")),
                new Proration.SalarySegment(LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 31), new BigDecimal("1500000"))));
        assertThat(m).isEqualByComparingTo("117741.94");
        var joiner = Proration.ctcMonthly(may, List.of(
                new Proration.SalarySegment(LocalDate.of(2026, 5, 21), LocalDate.of(2026, 5, 31), new BigDecimal("1200000"))));
        assertThat(joiner).isEqualByComparingTo("100000.00"); // the 11 of 31 days is PAYABLE_DAYS' job
    }

    @Test
    void diff_explains_changes_and_flags_outliers() {
        UUID same = UUID.randomUUID();
        UUID raised = UUID.randomUUID();
        UUID joined = UUID.randomUUID();
        var base = Map.of(
                same, side("E1", "100.00", Map.of("BASIC", "100.00"), Map.of("LOP_DAYS", "0")),
                raised, side("E2", "1000.00", Map.of("BASIC", "1000.00"), Map.of("LOP_DAYS", "0", "CTC_ANNUAL", "12000")));
        var other = Map.of(
                same, side("E1", "100.00", Map.of("BASIC", "100.0"), Map.of("LOP_DAYS", "0.0")),
                raised, side("E2", "1300.00", Map.of("BASIC", "1300.00"), Map.of("LOP_DAYS", "0", "CTC_ANNUAL", "15600")),
                joined, side("E3", "500.00", Map.of("BASIC", "500.00"), Map.of()));

        var d = RunDiff.between(base, other, new BigDecimal("0.20"));

        assertThat(d.changed()).isEqualTo(1);
        assertThat(d.added()).isEqualTo(1);
        assertThat(d.outliers()).isEqualTo(2);
        assertThat(d.netFrom()).isEqualByComparingTo("1100.00");
        assertThat(d.netTo()).isEqualByComparingTo("1900.00");
        var e2 = d.employees().stream().filter(e -> e.code().equals("E2")).findFirst().orElseThrow();
        assertThat(e2.netDelta()).isEqualByComparingTo("300.00");
        assertThat(e2.explanation()).contains("CTC_ANNUAL 12000 -> 15600").contains("BASIC +300.00");
        assertThat(e2.outlier()).isTrue();
    }

    private static RunDiff.Side side(String code, String net, Map<String, String> lines, Map<String, String> inputs) {
        var l = new HashMap<String, BigDecimal>();
        lines.forEach((k, v) -> l.put(k, new BigDecimal(v)));
        return new RunDiff.Side(code, code + " Example", new BigDecimal(net), l, inputs);
    }
}
