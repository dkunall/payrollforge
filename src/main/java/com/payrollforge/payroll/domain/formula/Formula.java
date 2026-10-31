package com.payrollforge.payroll.domain.formula;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A parsed formula, evaluated by walking the tree with {@link BigDecimal}. There is no reflection, no script
 * engine and no way to reach anything except the variables and functions the context offers.
 *
 * <p>Rules: + - * are exact; division keeps {@value #DIVISION_SCALE} decimals (HALF_EVEN); {@code round(x, n)}
 * rounds HALF_UP to n decimals (0 when omitted); comparisons and && || give 1 or 0, and any non-zero value is
 * true; {@code if(c, a, b)} only evaluates the branch it takes.
 */
public final class Formula {

    public static final int DIVISION_SCALE = 10;
    private static final BigDecimal LIMIT = new BigDecimal("1e15");

    /** What a formula can see while it runs. */
    public interface Context {
        BigDecimal variable(String name);

        /** Annual tax on an annual taxable amount under the employee's regime. */
        BigDecimal annualTax(BigDecimal annualTaxable);
    }

    private final String source;
    private final Expr root;
    private final Set<String> variables;
    private final boolean usesAnnualTax;

    private Formula(String source, Expr root) {
        this.source = source;
        this.root = root;
        var vars = new LinkedHashSet<String>();
        this.usesAnnualTax = collect(root, vars);
        this.variables = Collections.unmodifiableSet(vars);
    }

    public static Formula parse(String source) {
        return new Formula(source.strip(), Parser.parse(source));
    }

    public String source() {
        return source;
    }

    /** Every identifier the formula reads, in first-use order. */
    public Set<String> variables() {
        return variables;
    }

    public boolean usesAnnualTax() {
        return usesAnnualTax;
    }

    public BigDecimal evaluate(Context ctx) {
        return eval(root, ctx);
    }

    private static boolean collect(Expr e, Set<String> vars) {
        return switch (e) {
            case Expr.Num n -> false;
            case Expr.Var v -> {
                vars.add(v.name());
                yield false;
            }
            case Expr.Op o -> o.args().stream().map(a -> collect(a, vars)).reduce(false, Boolean::logicalOr);
            case Expr.Call c -> c.fn().equals("annual_tax")
                    | c.args().stream().map(a -> collect(a, vars)).reduce(false, Boolean::logicalOr);
        };
    }

    private BigDecimal eval(Expr e, Context ctx) {
        BigDecimal v = switch (e) {
            case Expr.Num n -> n.value();
            case Expr.Var var -> {
                BigDecimal x = ctx.variable(var.name());
                if (x == null) {
                    throw new FormulaException("formula-unknown-variable", "no value for " + var.name());
                }
                yield x;
            }
            case Expr.Op o -> op(o, ctx);
            case Expr.Call c -> call(c, ctx);
        };
        if (v.abs().compareTo(LIMIT) > 0) {
            throw new FormulaException("formula-overflow", "value out of range in '" + source + "'");
        }
        return v;
    }

    private BigDecimal op(Expr.Op o, Context ctx) {
        List<Expr> a = o.args();
        if (o.op().equals("neg")) {
            return eval(a.get(0), ctx).negate();
        }
        if (o.op().equals("&&")) {
            return bool(truthy(eval(a.get(0), ctx)) && truthy(eval(a.get(1), ctx)));
        }
        if (o.op().equals("||")) {
            return bool(truthy(eval(a.get(0), ctx)) || truthy(eval(a.get(1), ctx)));
        }
        BigDecimal l = eval(a.get(0), ctx);
        BigDecimal r = eval(a.get(1), ctx);
        return switch (o.op()) {
            case "+" -> l.add(r);
            case "-" -> l.subtract(r);
            case "*" -> l.multiply(r);
            case "/" -> {
                if (r.signum() == 0) {
                    throw new FormulaException("formula-division-by-zero", "division by zero in '" + source + "'");
                }
                yield l.divide(r, DIVISION_SCALE, RoundingMode.HALF_EVEN);
            }
            case "<" -> bool(l.compareTo(r) < 0);
            case "<=" -> bool(l.compareTo(r) <= 0);
            case ">" -> bool(l.compareTo(r) > 0);
            case ">=" -> bool(l.compareTo(r) >= 0);
            case "==" -> bool(l.compareTo(r) == 0);
            case "!=" -> bool(l.compareTo(r) != 0);
            default -> throw new IllegalStateException(o.op());
        };
    }

    private BigDecimal call(Expr.Call c, Context ctx) {
        List<Expr> a = c.args();
        return switch (c.fn()) {
            case "if" -> truthy(eval(a.get(0), ctx)) ? eval(a.get(1), ctx) : eval(a.get(2), ctx);
            case "min" -> a.stream().map(x -> eval(x, ctx)).reduce(BigDecimal::min).orElseThrow();
            case "max" -> a.stream().map(x -> eval(x, ctx)).reduce(BigDecimal::max).orElseThrow();
            case "abs" -> eval(a.get(0), ctx).abs();
            case "floor" -> eval(a.get(0), ctx).setScale(0, RoundingMode.FLOOR);
            case "ceil" -> eval(a.get(0), ctx).setScale(0, RoundingMode.CEILING);
            case "round" -> {
                int places = 0;
                if (a.size() == 2) {
                    BigDecimal p = eval(a.get(1), ctx);
                    if (p.stripTrailingZeros().scale() > 0 || p.signum() < 0 || p.intValue() > 6) {
                        throw new FormulaException("formula-arity", "round() places must be a whole number 0..6");
                    }
                    places = p.intValue();
                }
                yield eval(a.get(0), ctx).setScale(places, RoundingMode.HALF_UP);
            }
            case "annual_tax" -> ctx.annualTax(eval(a.get(0), ctx));
            default -> throw new IllegalStateException(c.fn());
        };
    }

    private static boolean truthy(BigDecimal v) {
        return v.signum() != 0;
    }

    private static BigDecimal bool(boolean b) {
        return b ? BigDecimal.ONE : BigDecimal.ZERO;
    }

    @Override
    public String toString() {
        return source;
    }
}
