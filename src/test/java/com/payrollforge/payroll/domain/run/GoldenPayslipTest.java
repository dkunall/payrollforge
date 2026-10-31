package com.payrollforge.payroll.domain.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrollforge.payroll.domain.structure.PayslipLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every line of every golden payslip must match, to the paisa, what an independent Python Decimal implementation
 * (src/test/resources/golden/oracle.py) computed from the same documented rules.
 */
class GoldenPayslipTest {

    static Stream<JsonNode> cases() throws IOException {
        try (InputStream in = GoldenPayslipTest.class.getResourceAsStream("/golden/golden-payslips.json")) {
            List<JsonNode> list = new ArrayList<>();
            new ObjectMapper().readTree(in).forEach(list::add);
            return list.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matches_the_oracle(JsonNode c) {
        var snapshot = new EmployeeSnapshot(UUID.nameUUIDFromBytes(c.get("name").asText().getBytes()), "E1",
                "Golden Case", "2026-05", Fixtures.SPECS.get(c.get("structure").asText()), strings(c.get("inputs")),
                c.get("regime").asText(), c.get("declared").asText(), arrears(c), List.of(), List.of());

        var result = PayslipComputation.current(snapshot, Fixtures.RULES, Fixtures.COMPILER).run();

        JsonNode exp = c.get("expected");
        var actualLines = new TreeMap<String, String>();
        for (PayslipLine l : result.payslip().lines()) {
            actualLines.put(l.code(), l.amount().toPlainString());
        }
        assertThat(actualLines).isEqualTo(new TreeMap<>(strings(exp.get("lines"))));
        assertThat(result.payslip().gross()).isEqualByComparingTo(exp.get("gross").asText());
        assertThat(result.payslip().deductions()).isEqualByComparingTo(exp.get("deductions").asText());
        assertThat(result.payslip().net()).isEqualByComparingTo(exp.get("net").asText());
        assertThat(result.payslip().net().scale()).isEqualTo(2);

        var expArrears = new ArrayList<String>();
        exp.get("arrears").forEach(a -> expArrears.add(a.get("period").asText() + " " + a.get("component").asText()
                + " " + a.get("kind").asText() + " " + new BigDecimal(a.get("amount").asText()).toPlainString()));
        assertThat(result.arrears().stream().map(a -> a.period() + " " + a.component() + " " + a.kind() + " "
                + a.amount().toPlainString()).toList()).containsExactlyInAnyOrderElementsOf(expArrears);
    }

    private static List<ArrearsItem> arrears(JsonNode c) {
        var out = new ArrayList<ArrearsItem>();
        if (c.has("arrears")) {
            for (JsonNode a : c.get("arrears")) {
                out.add(new ArrearsItem(a.get("period").asText(), Fixtures.SPECS.get(a.get("structure").asText()),
                        strings(a.get("inputs")), strings(a.get("baseline"))));
            }
        }
        return out;
    }

    private static Map<String, String> strings(JsonNode n) {
        var m = new LinkedHashMap<String, String>();
        n.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
        return m;
    }
}
