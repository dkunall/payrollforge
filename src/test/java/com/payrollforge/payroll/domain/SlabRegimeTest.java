package com.payrollforge.payroll.domain;

import com.payrollforge.payroll.domain.tax.RegimeName;
import com.payrollforge.payroll.domain.tax.Slab;
import com.payrollforge.payroll.domain.tax.SlabRegime;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlabRegimeTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    /** Made-up slabs: 0% to 3L, 10% to 6L, 20% above, 50k standard deduction, 1L cap, 4% cess. */
    private static final SlabRegime REGIME = new SlabRegime(RegimeName.OLD, d("50000"), d("100000"), d("0.04"), List.of(
            new Slab(d("0"), d("300000"), d("0")),
            new Slab(d("300000"), d("600000"), d("0.10")),
            new Slab(d("600000"), null, d("0.20"))));

    @Test
    void taxesEachSliceAtItsOwnRate() {
        // 9.5L - 50k = 9L taxable: 0 + 30k + 60k = 90k, cess 3.6k
        assertThat(REGIME.annualTax(d("950000"), BigDecimal.ZERO)).isEqualByComparingTo("93600");
    }

    @Test
    void boundariesAndZero() {
        assertThat(REGIME.annualTax(d("350000"), BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(REGIME.annualTax(d("350001"), BigDecimal.ZERO)).isEqualByComparingTo("0"); // 0.104 rounds to 0
        assertThat(REGIME.annualTax(d("650000"), BigDecimal.ZERO)).isEqualByComparingTo("31200");
        assertThat(REGIME.annualTax(d("10000"), BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    void declaredDeductionsAreCapped() {
        assertThat(REGIME.annualTax(d("950000"), d("100000")))
                .isEqualByComparingTo(REGIME.annualTax(d("950000"), d("250000")))
                .isEqualByComparingTo("72800"); // 8L taxable: 30k + 40k = 70k, cess 2.8k
        assertThat(REGIME.annualTax(d("950000"), d("-5"))).isEqualByComparingTo("93600");
    }

    @Test
    void rejectsGapsAndClosedLastSlab() {
        assertThatThrownBy(() -> new SlabRegime(RegimeName.NEW, d("0"), d("0"), d("0"), List.of(
                new Slab(d("0"), d("100"), d("0")), new Slab(d("200"), null, d("0.1")))))
                .hasMessageContaining("contiguous");
        assertThatThrownBy(() -> new SlabRegime(RegimeName.NEW, d("0"), d("0"), d("0"), List.of(
                new Slab(d("0"), d("100"), d("0"))))).hasMessageContaining("open-ended");
    }
}
