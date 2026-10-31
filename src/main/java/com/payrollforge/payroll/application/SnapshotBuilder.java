package com.payrollforge.payroll.application;

import com.payrollforge.payroll.domain.run.ArrearsItem;
import com.payrollforge.payroll.domain.run.EmployeeSnapshot;
import com.payrollforge.payroll.domain.run.PayslipComputation;
import com.payrollforge.payroll.domain.run.Period;
import com.payrollforge.payroll.domain.run.Proration;
import com.payrollforge.payroll.domain.run.Proration.SalarySegment;
import com.payrollforge.payroll.domain.run.StructureSpec;
import com.payrollforge.payroll.domain.structure.Inputs;
import com.payrollforge.payroll.domain.structure.SalaryStructure;
import com.payrollforge.payroll.domain.tax.TaxRules;
import com.payrollforge.payroll.application.PayrollSources.PaySegment;
import com.payrollforge.payroll.application.PayrollSources.Person;
import com.payrollforge.shared.config.PayrollForgeProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * Freezes everything a period's payslips depend on into one snapshot per employee. The same builder runs at start
 * and again at approve and finalize; a different hash means something changed underneath the run.
 */
@Component
public class SnapshotBuilder {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public record Built(Map<UUID, EmployeeSnapshot> snapshots, List<String> warnings) {
    }

    private final PayrollSources sources;
    private final PayrollStore store;
    private final StructureService structures;
    private final TaxDeclarationStore declarations;
    private final PayrollForgeProperties props;

    public SnapshotBuilder(PayrollSources sources, PayrollStore store, StructureService structures,
                           TaxDeclarationStore declarations, PayrollForgeProperties props) {
        this.sources = sources;
        this.store = store;
        this.structures = structures;
        this.declarations = declarations;
        this.props = props;
    }

    public Built build(String entity, YearMonth period, TaxRules rules) {
        LocalDate from = period.atDay(1);
        LocalDate to = period.atEndOfMonth();
        Function<StructureSpec, SalaryStructure> compiler = structures.compiler();
        List<Person> people = new ArrayList<>(sources.employedBetween(from, to));
        people.sort(Comparator.comparing(Person::code));
        var ids = people.stream().map(Person::id).toList();
        var segments = sources.segments(people, from, to);
        var days = store.payableDays(ids, from, to);
        var ytd = store.ytd(ids, entity, Period.fyStart(period), period);
        var decl = declarations.forYear(ids, Period.fyLabel(period));
        var settled = store.settledRuns(entity, period.minusMonths(props.payroll().arrearsLookbackMonths()),
                period.minusMonths(1));
        var settledArrears = settled.isEmpty() ? Map.<UUID, Map<String, Map<String, BigDecimal>>>of()
                : store.settledArrears(ids);
        var earlierInputs = new TreeMap<YearMonth, Map<UUID, EmployeeSnapshot>>();
        var earlierAmounts = new TreeMap<YearMonth, Map<UUID, Map<String, BigDecimal>>>();
        settled.forEach((p, runId) -> {
            earlierInputs.put(p, store.inputs(runId, ids));
            earlierAmounts.put(p, store.payslipAmounts(runId, ids));
        });
        var earlierSegments = new HashMap<YearMonth, Map<UUID, List<PaySegment>>>();
        for (YearMonth p : settled.keySet()) {
            earlierSegments.put(p, sources.segments(people, p.atDay(1), p.atEndOfMonth()));
        }

        var out = new LinkedHashMap<UUID, EmployeeSnapshot>();
        var warnings = new ArrayList<String>();
        int openDays = 0;
        int openPeople = 0;
        for (Person p : people) {
            List<PaySegment> segs = segments.getOrDefault(p.id(), List.of());
            if (segs.isEmpty()) {
                warnings.add(p.code() + ": no salary version covers the employed days; not paid this period");
                continue;
            }
            LocalDate start = segs.getFirst().from();
            LocalDate end = segs.getLast().to();
            long employed = ChronoUnit.DAYS.between(start, end) + 1;
            BigDecimal lop = ZERO;
            int open = 0;
            var payable = days.getOrDefault(p.id(), Map.of());
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                BigDecimal v = payable.get(d);
                if (v == null) {
                    open++;
                } else {
                    lop = lop.add(BigDecimal.ONE.subtract(v));
                }
            }
            if (open > 0) {
                openDays += open;
                openPeople++;
            }
            lop = lop.setScale(2);
            StructureSpec spec = structures.effectiveOn(segs.getLast().structureCode(), to);

            var in = new TreeMap<String, String>();
            in.put(Inputs.CTC_ANNUAL, money(segs.getLast().ctcAnnual()));
            in.put(Inputs.CTC_MONTHLY, money(Proration.ctcMonthly(period, salary(segs))));
            in.put(Inputs.PERIOD_DAYS, Integer.toString(period.lengthOfMonth()));
            in.put(Inputs.PAYABLE_DAYS, BigDecimal.valueOf(employed).subtract(lop).setScale(2).toPlainString());
            in.put(Inputs.LOP_DAYS, lop.toPlainString());
            var encash = sources.encashments(p.id());
            var claims = sources.reimbursements(p.id());
            in.put(Inputs.ENCASH_DAYS, sum(encash).toPlainString());
            in.put(Inputs.REIMBURSEMENT, sum(claims).toPlainString());
            in.put(Inputs.MONTHS_LEFT_IN_FY, Integer.toString(Period.monthsLeftInFy(period)));
            var y = ytd.getOrDefault(p.id(), new PayrollStore.Ytd(ZERO, ZERO));
            in.put(Inputs.YTD_TAXABLE, money(y.taxable()));
            in.put(Inputs.YTD_TDS, money(y.tds()));

            var d = decl.get(p.id());
            String regime = d == null ? "NEW" : d.regime();
            String declared = d == null ? "0.00" : money(d.total());

            var base = new EmployeeSnapshot(p.id(), p.code(), p.name(), period.toString(), spec, in, regime, declared,
                    List.of(), ids(claims), ids(encash));
            var arrears = new ArrayList<ArrearsItem>();
            for (YearMonth q : settled.keySet()) {
                EmployeeSnapshot original = earlierInputs.get(q).get(p.id());
                List<PaySegment> qsegs = earlierSegments.get(q).getOrDefault(p.id(), List.of());
                if (original == null || qsegs.isEmpty()) {
                    continue;
                }
                var qin = new TreeMap<>(original.inputs());
                String ctcM = money(Proration.ctcMonthly(q, salary(qsegs)));
                String ctcA = money(qsegs.getLast().ctcAnnual());
                if (ctcM.equals(qin.get(Inputs.CTC_MONTHLY)) && ctcA.equals(qin.get(Inputs.CTC_ANNUAL))) {
                    continue;
                }
                qin.put(Inputs.CTC_MONTHLY, ctcM);
                qin.put(Inputs.CTC_ANNUAL, ctcA);
                qin.put(Inputs.ARREARS, "0.00");
                qin.put(Inputs.ARREARS_DEDUCTIONS, "0.00");
                var paid = new TreeMap<String, BigDecimal>(earlierAmounts.get(q).getOrDefault(p.id(), Map.of()));
                settledArrears.getOrDefault(p.id(), Map.of()).getOrDefault(q.toString(), Map.of())
                        .forEach((c, a) -> paid.merge(c, a, BigDecimal::add));
                var baseline = new TreeMap<String, String>();
                paid.forEach((c, a) -> baseline.put(c, money(a)));
                var item = new ArrearsItem(q.toString(), original.structure(), qin, baseline);
                if (!PayslipComputation.retroDelta(original, item, rules, compiler).isEmpty()) {
                    arrears.add(item);
                }
            }
            out.put(p.id(), new EmployeeSnapshot(base.employeeId(), base.code(), base.name(), base.period(),
                    base.structure(), base.inputs(), base.regime(), base.declaredDeductions(), arrears,
                    base.reimbursementIds(), base.encashmentIds()));
        }
        if (openDays > 0) {
            warnings.add(openDays + " employee-days across " + openPeople
                    + " people have no closed attendance day; they are paid as payable");
        }
        return new Built(out, warnings);
    }

    private static List<SalarySegment> salary(List<PaySegment> segs) {
        return segs.stream().map(s -> new SalarySegment(s.from(), s.to(), s.ctcAnnual())).toList();
    }

    private static BigDecimal sum(List<PayrollSources.Item> items) {
        return items.stream().map(PayrollSources.Item::amount).reduce(ZERO, BigDecimal::add).setScale(2);
    }

    private static List<String> ids(List<PayrollSources.Item> items) {
        return items.stream().map(i -> i.id().toString()).sorted().toList();
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
