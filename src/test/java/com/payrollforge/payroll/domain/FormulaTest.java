package com.payrollforge.payroll.domain;

import com.payrollforge.payroll.domain.formula.Formula;
import com.payrollforge.payroll.domain.formula.FormulaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormulaTest {

    private static final Map<String, BigDecimal> VARS = Map.of(
            "BASIC", new BigDecimal("40000"), "CTC_MONTHLY", new BigDecimal("100000"),
            "PAYABLE_DAYS", new BigDecimal("28"), "PERIOD_DAYS", new BigDecimal("30"), "ZERO", BigDecimal.ZERO);

    private static BigDecimal eval(String src) {
        return Formula.parse(src).evaluate(new Formula.Context() {
            @Override
            public BigDecimal variable(String name) {
                return VARS.get(name);
            }

            @Override
            public BigDecimal annualTax(BigDecimal x) {
                return x.multiply(new BigDecimal("0.1"));
            }
        });
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "1 + 2 * 3 ; 7",
            "(1 + 2) * 3 ; 9",
            "10 - 4 - 3 ; 3",
            "2 * -3 ; -6",
            "--2 ; 2",
            "-(2 + 3) * 2 ; -10",
            "BASIC * 0.5 ; 20000.0",
            "round(CTC_MONTHLY * PAYABLE_DAYS / PERIOD_DAYS, 2) ; 93333.33",
            "round(2.5) ; 3",
            "round(-2.5) ; -3",
            "floor(2.9) + ceil(2.1) ; 5",
            "min(BASIC, 15000) * 0.12 ; 1800.00",
            "max(1, 7, 3) ; 7",
            "abs(-4.5) ; 4.5",
            "if(BASIC > 30000, 1, 2) ; 1",
            "if(BASIC <= 30000, 1, 2) ; 2",
            "1 < 2 && 2 < 1 ; 0",
            "1 < 2 || 2 < 1 ; 1",
            "3 == 3.00 ; 1",
            "3 != 3 ; 0",
            "1 + 2 > 2 ; 1",
            "annual_tax(1000) ; 100.0",
            "0.1 + 0.2 ; 0.3"})
    void evaluatesWithPrecedenceAndExactDecimals(String src, String expected) {
        assertThat(eval(src)).isEqualByComparingTo(expected);
    }

    @Test
    void divisionKeepsTenDecimalsHalfEven() {
        assertThat(eval("1 / 3")).isEqualTo(new BigDecimal("0.3333333333"));
        assertThat(eval("2 / 3")).isEqualTo(new BigDecimal("0.6666666667"));
    }

    @Test
    void ifOnlyEvaluatesTheBranchItTakes() {
        assertThat(eval("if(ZERO == 0, 0, BASIC / ZERO)")).isEqualByComparingTo("0");
    }

    @Test
    void reportsVariablesAndTaxUse() {
        var f = Formula.parse("round(max(BASIC, CTC_MONTHLY) / 12) + annual_tax(BASIC)");
        assertThat(f.variables()).containsExactly("BASIC", "CTC_MONTHLY");
        assertThat(f.usesAnnualTax()).isTrue();
        assertThat(Formula.parse("BASIC").usesAnnualTax()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1 +", "* 2", "(1 + 2", "1 + 2)", "1 2", "min(1)", "round(1, 2, 3)", "if(1, 2)",
            "BASIC(1)", "exec(1)", "System.exit(0)", "1 ; 2", "\"x\"", "a = 1", "min(,1)", "max(1,)", "()",
            "round(1, 0.5)", "1 $ 2"})
    void rejectsAnythingOutsideTheGrammar(String src) {
        assertThatThrownBy(() -> eval(src)).isInstanceOf(FormulaException.class);
    }

    @Test
    void namesTheProblem() {
        assertThatThrownBy(() -> Formula.parse("java(1)")).isInstanceOf(FormulaException.class)
                .hasMessageContaining("unknown function 'java'").extracting("code").isEqualTo("formula-function");
        assertThatThrownBy(() -> eval("BASIC / ZERO")).extracting("code").isEqualTo("formula-division-by-zero");
        assertThatThrownBy(() -> eval("NOPE + 1")).extracting("code").isEqualTo("formula-unknown-variable");
        assertThatThrownBy(() -> Formula.parse("1 +* 2")).extracting("code").isEqualTo("formula-syntax");
    }

    @Test
    void limitsLengthDepthAndMagnitude() {
        assertThatThrownBy(() -> Formula.parse("1+".repeat(300) + "1")).hasMessageContaining("longer than");
        assertThatThrownBy(() -> Formula.parse("(".repeat(40) + "1" + ")".repeat(40)))
                .hasMessageContaining("nesting deeper");
        assertThatThrownBy(() -> eval("99999999 * 99999999")).extracting("code").isEqualTo("formula-overflow");
    }
}
