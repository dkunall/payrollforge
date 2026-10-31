package com.payrollforge.payroll.domain.formula;

import java.util.Map;

/**
 * The whitelist. A name that is not here does not parse. {@code annual_tax} is evaluated by the caller's
 * {@link Formula.Context}, so the tax regime stays a strategy outside the grammar.
 */
public final class Functions {

    record Arity(int min, int max) {
    }

    static final Map<String, Arity> ALLOWED = Map.of(
            "min", new Arity(2, 8),
            "max", new Arity(2, 8),
            "round", new Arity(1, 2),
            "floor", new Arity(1, 1),
            "ceil", new Arity(1, 1),
            "abs", new Arity(1, 1),
            "if", new Arity(3, 3),
            "annual_tax", new Arity(1, 1));

    private Functions() {
    }
}
