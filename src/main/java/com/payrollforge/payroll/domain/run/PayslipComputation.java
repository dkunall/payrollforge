package com.payrollforge.payroll.domain.run;

import com.payrollforge.payroll.domain.structure.Calculator;
import com.payrollforge.payroll.domain.structure.ComponentKind;
import com.payrollforge.payroll.domain.structure.Computation;
import com.payrollforge.payroll.domain.structure.Inputs;
import com.payrollforge.payroll.domain.structure.PayslipLine;
import com.payrollforge.payroll.domain.structure.SalaryStructure;
import com.payrollforge.payroll.domain.tax.RegimeName;
import com.payrollforge.payroll.domain.tax.TaxRules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Template Method: every payslip computation picks a structure, assembles inputs and a tax function, then runs
 * the same calculator. The current period adds arrears on top; a retro recomputation of an old period does not.
 */
public abstract class PayslipComputation {

    /** Per component and period, what an arrears line paid. Stored so later runs count it as already paid. */
    public record ArrearsLine(String period, String component, ComponentKind kind, BigDecimal amount) {
    }

    public record Result(Computation payslip, Map<String, BigDecimal> inputs, List<ArrearsLine> arrears) {
    }

    protected final EmployeeSnapshot snapshot;
    protected final TaxRules rules;
    protected final Function<StructureSpec, SalaryStructure> compiler;

    protected PayslipComputation(EmployeeSnapshot snapshot, TaxRules rules,
                                 Function<StructureSpec, SalaryStructure> compiler) {
        this.snapshot = snapshot;
        this.rules = rules;
        this.compiler = compiler;
    }

    public final Result run() {
        SalaryStructure structure = compiler.apply(spec());
        Map<String, BigDecimal> inputs = inputs();
        Computation c = Calculator.compute(structure, inputs, taxFunction());
        return new Result(c, inputs, extraLines());
    }

    protected abstract StructureSpec spec();

    protected abstract Map<String, BigDecimal> inputs();

    protected List<ArrearsLine> extraLines() {
        return List.of();
    }

    protected UnaryOperator<BigDecimal> taxFunction() {
        var regime = rules.regime(RegimeName.valueOf(snapshot.regime()));
        var declared = new BigDecimal(snapshot.declaredDeductions());
        return annual -> regime.annualTax(annual, declared);
    }

    static Map<String, BigDecimal> decimals(Map<String, String> in) {
        var out = new HashMap<String, BigDecimal>();
        in.forEach((k, v) -> out.put(k, new BigDecimal(v)));
        return out;
    }

    /** This period's payslip, with arrears from earlier settled periods folded into ARREARS inputs. */
    public static PayslipComputation current(EmployeeSnapshot s, TaxRules rules,
                                             Function<StructureSpec, SalaryStructure> compiler) {
        return new PayslipComputation(s, rules, compiler) {
            private final List<ArrearsLine> lines = new ArrayList<>();

            @Override
            protected StructureSpec spec() {
                return s.structure();
            }

            @Override
            protected Map<String, BigDecimal> inputs() {
                var in = decimals(s.inputs());
                BigDecimal earnings = Calculator.money(BigDecimal.ZERO);
                BigDecimal deductions = Calculator.money(BigDecimal.ZERO);
                for (ArrearsItem item : s.arrears()) {
                    for (ArrearsLine l : retroDelta(s, item, rules, compiler)) {
                        lines.add(l);
                        if (l.kind() == ComponentKind.EARNING) {
                            earnings = earnings.add(l.amount());
                        } else {
                            deductions = deductions.add(l.amount());
                        }
                    }
                }
                in.put(Inputs.ARREARS, earnings);
                in.put(Inputs.ARREARS_DEDUCTIONS, deductions);
                return in;
            }

            @Override
            protected List<ArrearsLine> extraLines() {
                return List.copyOf(lines);
            }
        };
    }

    /** An earlier period recomputed with today's records, nothing else changed. */
    public static PayslipComputation retro(EmployeeSnapshot s, ArrearsItem item, TaxRules rules,
                                           Function<StructureSpec, SalaryStructure> compiler) {
        return new PayslipComputation(s, rules, compiler) {
            @Override
            protected StructureSpec spec() {
                return item.structure();
            }

            @Override
            protected Map<String, BigDecimal> inputs() {
                return decimals(item.inputs());
            }
        };
    }

    /**
     * Recomputed minus already paid, per component, skipping tax components: tax on arrears is settled by the
     * current month's projection over the year, so re-running an old month's TDS would count it twice.
     */
    public static List<ArrearsLine> retroDelta(EmployeeSnapshot s, ArrearsItem item, TaxRules rules,
                                               Function<StructureSpec, SalaryStructure> compiler) {
        SalaryStructure structure = compiler.apply(item.structure());
        Computation now = retro(s, item, rules, compiler).run().payslip();
        var byCode = new LinkedHashMap<String, PayslipLine>();
        now.lines().forEach(l -> byCode.put(l.code(), l));
        var codes = new TreeSet<>(byCode.keySet());
        codes.addAll(item.baseline().keySet());
        var out = new ArrayList<ArrearsLine>();
        for (String code : codes) {
            if (structure.isTaxComponent(code)) {
                continue;
            }
            PayslipLine l = byCode.get(code);
            BigDecimal recomputed = l == null ? BigDecimal.ZERO : l.amount();
            BigDecimal paid = new BigDecimal(item.baseline().getOrDefault(code, "0"));
            BigDecimal delta = Calculator.money(recomputed.subtract(paid));
            if (delta.signum() != 0) {
                ComponentKind kind = l != null ? l.kind() : kindInSpec(item.structure(), code);
                if (kind != ComponentKind.INFO) {
                    out.add(new ArrearsLine(item.period(), code, kind, delta));
                }
            }
        }
        return out;
    }

    private static ComponentKind kindInSpec(StructureSpec spec, String code) {
        return spec.components().stream().filter(c -> c.code().equals(code)).map(c -> c.kind()).findFirst()
                .orElse(ComponentKind.EARNING);
    }
}
