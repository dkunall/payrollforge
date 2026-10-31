package com.payrollforge.payroll.domain.formula;

import java.math.BigDecimal;
import java.util.List;

/** The parsed tree. Evaluation lives in {@link Formula}; this is only shape. */
sealed interface Expr {

    record Num(BigDecimal value) implements Expr {
    }

    record Var(String name) implements Expr {
    }

    /** {@code op} is one of + - * / < <= > >= == != && || or "neg" for unary minus. */
    record Op(String op, List<Expr> args) implements Expr {
    }

    record Call(String fn, List<Expr> args) implements Expr {
    }
}
