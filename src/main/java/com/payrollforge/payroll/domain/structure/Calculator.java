package com.payrollforge.payroll.domain.structure;

import com.payrollforge.payroll.domain.formula.Formula;
import com.payrollforge.payroll.domain.formula.FormulaException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Runs a compiled structure for one employee. Earnings and deductions are rounded to paise (HALF_UP) as soon as
 * they are computed, so what later formulas read is exactly what the payslip shows. INFO values keep their
 * precision because they are usually rates or factors.
 */
public final class Calculator {

    private Calculator() {
    }

    public static Computation compute(SalaryStructure s, Map<String, BigDecimal> inputs,
                                      UnaryOperator<BigDecimal> annualTax) {
        for (String name : Inputs.PROVIDED) {
            if (!inputs.containsKey(name)) {
                throw new FormulaException("formula-unknown-variable", "input " + name + " was not supplied");
            }
        }
        var values = new HashMap<String, BigDecimal>(inputs);
        Formula.Context ctx = new Formula.Context() {
            @Override
            public BigDecimal variable(String name) {
                return values.get(name);
            }

            @Override
            public BigDecimal annualTax(BigDecimal annualTaxable) {
                if (annualTax == null) {
                    throw new FormulaException("formula-function", "annual_tax() is not available here");
                }
                return annualTax.apply(annualTaxable);
            }
        };
        var lines = new ArrayList<PayslipLine>();
        BigDecimal gross = money(BigDecimal.ZERO);
        BigDecimal deductions = money(BigDecimal.ZERO);
        var ordered = new LinkedHashMap<String, BigDecimal>();
        for (ComponentGraph.Node n : s.graph().order()) {
            BigDecimal v;
            if (n.aggregate()) {
                v = n.deps().stream().map(values::get).reduce(money(BigDecimal.ZERO), BigDecimal::add);
            } else {
                try {
                    v = n.formula().evaluate(ctx);
                } catch (FormulaException e) {
                    throw new FormulaException(e.code(), n.name() + ": " + e.getMessage());
                }
                ComponentDef d = n.def();
                if (d.kind() != ComponentKind.INFO) {
                    v = money(v);
                    lines.add(new PayslipLine(d.code(), d.label(), d.kind(), v, d.taxable()));
                    if (d.kind() == ComponentKind.EARNING) {
                        gross = gross.add(v);
                    } else {
                        deductions = deductions.add(v);
                    }
                }
            }
            values.put(n.name(), v);
            ordered.put(n.name(), v);
        }
        return new Computation(java.util.List.copyOf(lines), gross, deductions, gross.subtract(deductions),
                java.util.Collections.unmodifiableMap(ordered));
    }

    public static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
