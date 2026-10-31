package com.payrollforge.payroll.domain.run;

import com.payrollforge.payroll.domain.structure.ComponentDef;
import com.payrollforge.payroll.domain.structure.ComponentKind;
import com.payrollforge.payroll.domain.structure.SalaryStructure;
import com.payrollforge.payroll.domain.tax.RegimeName;
import com.payrollforge.payroll.domain.tax.Slab;
import com.payrollforge.payroll.domain.tax.SlabRegime;
import com.payrollforge.payroll.domain.tax.TaxRules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.payrollforge.payroll.domain.structure.ComponentKind.DEDUCTION;
import static com.payrollforge.payroll.domain.structure.ComponentKind.EARNING;
import static com.payrollforge.payroll.domain.structure.ComponentKind.INFO;

/** Structures and tax tables shared by the golden test; the Python oracle writes the same rules out by hand. */
final class Fixtures {

    private Fixtures() {
    }

    static final String TDS = "max(0, round((annual_tax(YTD_TAXABLE + TAXABLE_GROSS + RECURRING * (MONTHS_LEFT_IN_FY - 1))"
            + " - YTD_TDS) / MONTHS_LEFT_IN_FY, 0))";

    static final StructureSpec STD = new StructureSpec("STD", 1, List.of(
            c("FACTOR", INFO, "PAYABLE_DAYS / PERIOD_DAYS", false),
            c("BASIC", EARNING, "round(CTC_MONTHLY * 0.40 * FACTOR, 0)", true),
            c("HRA", EARNING, "round(BASIC * 0.50, 0)", true),
            c("PF_ER", INFO, "round(min(BASIC, 15000) * 0.12, 0)", false),
            c("SPECIAL", EARNING, "max(0, round(CTC_MONTHLY * FACTOR, 0) - BASIC - HRA - PF_ER)", true),
            c("LEAVE_ENCASH", EARNING, "round(ENCASH_DAYS * CTC_MONTHLY * 0.40 / 30, 0)", true),
            c("ARREARS_PAY", EARNING, "ARREARS", true),
            c("REIMB", EARNING, "REIMBURSEMENT", false),
            c("PF", DEDUCTION, "round(min(BASIC, 15000) * 0.12, 0)", false),
            c("ESI", DEDUCTION, "if(GROSS <= 21000, ceil(GROSS * 0.0075), 0)", false),
            c("PT", DEDUCTION, "if(GROSS > 15000, 200, 0)", false),
            c("ARREARS_DED", DEDUCTION, "ARREARS_DEDUCTIONS", false),
            c("RECURRING", INFO, "BASIC + HRA + SPECIAL", false),
            c("TDS", DEDUCTION, TDS, false)));

    static final StructureSpec INTERN = new StructureSpec("INTERN", 1, List.of(
            c("STIPEND", EARNING, "round(CTC_MONTHLY * PAYABLE_DAYS / PERIOD_DAYS, 2)", true),
            c("ARREARS_PAY", EARNING, "ARREARS", true),
            c("REIMB", EARNING, "REIMBURSEMENT", false),
            c("RECURRING", INFO, "STIPEND", false),
            c("TDS", DEDUCTION, TDS, false)));

    static final Map<String, StructureSpec> SPECS = Map.of("STD", STD, "INTERN", INTERN);

    static final TaxRules RULES = new TaxRules("illustrative-test", Map.of(
            RegimeName.NEW, new SlabRegime(RegimeName.NEW, d("75000"), d("0"), d("0.04"), List.of(
                    slab("0", "400000", "0"), slab("400000", "800000", "0.05"), slab("800000", "1200000", "0.10"),
                    slab("1200000", "1600000", "0.15"), slab("1600000", "2000000", "0.20"),
                    slab("2000000", "2400000", "0.25"), slab("2400000", null, "0.30"))),
            RegimeName.OLD, new SlabRegime(RegimeName.OLD, d("50000"), d("150000"), d("0.04"), List.of(
                    slab("0", "250000", "0"), slab("250000", "500000", "0.05"), slab("500000", "1000000", "0.20"),
                    slab("1000000", null, "0.30")))));

    static final Function<StructureSpec, SalaryStructure> COMPILER =
            s -> SalaryStructure.compile(s.code(), s.version(), LocalDate.of(2026, 4, 1), s.components());

    private static ComponentDef c(String code, ComponentKind kind, String formula, boolean taxable) {
        return new ComponentDef(code, code, kind, formula, taxable);
    }

    static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static Slab slab(String from, String to, String rate) {
        return new Slab(d(from), to == null ? null : d(to), d(rate));
    }
}
