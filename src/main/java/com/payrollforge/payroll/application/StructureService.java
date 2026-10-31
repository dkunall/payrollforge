package com.payrollforge.payroll.application;

import com.payrollforge.payroll.domain.run.Canonical;
import com.payrollforge.payroll.domain.run.StructureSpec;
import com.payrollforge.payroll.domain.structure.ComponentDef;
import com.payrollforge.payroll.domain.structure.ComponentKind;
import com.payrollforge.payroll.domain.structure.SalaryStructure;
import com.payrollforge.payroll.domain.structure.StructureException;
import com.payrollforge.shared.domain.DomainRuleException;
import com.payrollforge.shared.error.ApiException;
import com.payrollforge.shared.security.Caller;
import com.payrollforge.shared.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Salary structures are versioned and immutable: a change is a new version with an effective date, and runs copy
 * the whole definition into their snapshot so old payslips recompute with the formulas they were paid with.
 */
@Service
public class StructureService {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,19}");
    static final String TDS = "max(0, round((annual_tax(YTD_TAXABLE + TAXABLE_GROSS + RECURRING * (MONTHS_LEFT_IN_FY - 1))"
            + " - YTD_TDS) / MONTHS_LEFT_IN_FY, 0))";

    private final StructureStore store;
    private final Clock clock;
    private final Map<String, SalaryStructure> compiled = new ConcurrentHashMap<>();

    public StructureService(StructureStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional
    public StructureStore.Version create(Caller caller, String code, LocalDate effectiveFrom, List<ComponentDef> components) {
        caller.require(Role.HR, Role.PAYROLL_MAKER);
        if (!CODE.matcher(code).matches()) {
            throw ApiException.invalid("code must match " + CODE.pattern());
        }
        if (effectiveFrom.getDayOfMonth() != 1) {
            throw ApiException.invalid("effectiveFrom must be the first day of a month");
        }
        SalaryStructure s;
        try {
            s = SalaryStructure.compile(code, 0, effectiveFrom, components);
        } catch (StructureException e) {
            throw new DomainRuleException(e.code(), e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new DomainRuleException("invalid-structure", e.getMessage());
        }
        int version = store.latestVersion(code) + 1;
        var spec = new StructureSpec(code, version, components);
        if (!store.insert(spec, effectiveFrom, s.graph().dependsOn(), caller.subject(), clock.instant())) {
            throw ApiException.conflict("structure-version-exists", "a concurrent change created this version first");
        }
        return store.versions(code).stream().filter(v -> v.spec().version() == version).findFirst().orElseThrow();
    }

    public List<StructureStore.Version> versions(Caller caller, String code) {
        caller.require(Role.HR, Role.PAYROLL_MAKER, Role.PAYROLL_CHECKER);
        return store.versions(code);
    }

    public StructureSpec effectiveOn(String code, LocalDate day) {
        return store.effectiveOn(code, day).orElseThrow(() -> new DomainRuleException("unknown-structure",
                "no version of salary structure " + code + " is effective on " + day));
    }

    /** Compiles a snapshot's structure; cached by content, so equal definitions compile once. */
    public Function<StructureSpec, SalaryStructure> compiler() {
        return spec -> compiled.computeIfAbsent(Canonical.sha256(spec.toMap()),
                k -> SalaryStructure.compile(spec.code(), spec.version(), LocalDate.of(2000, 1, 1), spec.components()));
    }

    /** Two illustrative structures so a fresh install can run payroll; skipped when the code already exists. */
    @Transactional
    public void ensureDefaults(LocalDate effectiveFrom) {
        if (store.latestVersion("STD") == 0) {
            insertDefault(new StructureSpec("STD", 1, List.of(
                    c("FACTOR", "Payable fraction", ComponentKind.INFO, "PAYABLE_DAYS / PERIOD_DAYS", false),
                    c("BASIC", "Basic", ComponentKind.EARNING, "round(CTC_MONTHLY * 0.40 * FACTOR, 0)", true),
                    c("HRA", "House rent allowance", ComponentKind.EARNING, "round(BASIC * 0.50, 0)", true),
                    c("PF_ER", "Employer PF", ComponentKind.INFO, "round(min(BASIC, 15000) * 0.12, 0)", false),
                    c("SPECIAL", "Special allowance", ComponentKind.EARNING,
                            "max(0, round(CTC_MONTHLY * FACTOR, 0) - BASIC - HRA - PF_ER)", true),
                    c("LEAVE_ENCASH", "Leave encashment", ComponentKind.EARNING,
                            "round(ENCASH_DAYS * CTC_MONTHLY * 0.40 / 30, 0)", true),
                    c("ARREARS_PAY", "Arrears", ComponentKind.EARNING, "ARREARS", true),
                    c("REIMB", "Reimbursements", ComponentKind.EARNING, "REIMBURSEMENT", false),
                    c("PF", "Provident fund", ComponentKind.DEDUCTION, "round(min(BASIC, 15000) * 0.12, 0)", false),
                    c("ESI", "ESI", ComponentKind.DEDUCTION, "if(GROSS <= 21000, ceil(GROSS * 0.0075), 0)", false),
                    c("PT", "Professional tax", ComponentKind.DEDUCTION, "if(GROSS > 15000, 200, 0)", false),
                    c("ARREARS_DED", "Arrears deductions", ComponentKind.DEDUCTION, "ARREARS_DEDUCTIONS", false),
                    c("RECURRING", "Recurring taxable pay", ComponentKind.INFO, "BASIC + HRA + SPECIAL", false),
                    c("TDS", "Income tax (TDS)", ComponentKind.DEDUCTION, TDS, false))), effectiveFrom);
        }
        if (store.latestVersion("INTERN") == 0) {
            insertDefault(new StructureSpec("INTERN", 1, List.of(
                    c("STIPEND", "Stipend", ComponentKind.EARNING, "round(CTC_MONTHLY * PAYABLE_DAYS / PERIOD_DAYS, 2)", true),
                    c("ARREARS_PAY", "Arrears", ComponentKind.EARNING, "ARREARS", true),
                    c("REIMB", "Reimbursements", ComponentKind.EARNING, "REIMBURSEMENT", false),
                    c("RECURRING", "Recurring taxable pay", ComponentKind.INFO, "STIPEND", false),
                    c("TDS", "Income tax (TDS)", ComponentKind.DEDUCTION, TDS, false))), effectiveFrom);
        }
    }

    private void insertDefault(StructureSpec spec, LocalDate effectiveFrom) {
        var s = SalaryStructure.compile(spec.code(), spec.version(), effectiveFrom, spec.components());
        store.insert(spec, effectiveFrom, s.graph().dependsOn(), "system:defaults", clock.instant());
    }

    private static ComponentDef c(String code, String label, ComponentKind kind, String formula, boolean taxable) {
        return new ComponentDef(code, label, kind, formula, taxable);
    }
}
