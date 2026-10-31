package com.payrollforge.payroll.domain.formula;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Shunting-yard: operators wait on a stack until something of lower precedence arrives, operands go straight
 * to the output. The output here is a stack of trees rather than RPN text, which is the same algorithm with
 * the reduction done as we go.
 */
final class Parser {

    static final int MAX_DEPTH = 32;

    private static final Map<String, Integer> PRECEDENCE = Map.ofEntries(
            Map.entry("||", 1), Map.entry("&&", 2),
            Map.entry("==", 3), Map.entry("!=", 3),
            Map.entry("<", 4), Map.entry("<=", 4), Map.entry(">", 4), Map.entry(">=", 4),
            Map.entry("+", 5), Map.entry("-", 5),
            Map.entry("*", 6), Map.entry("/", 6),
            Map.entry("neg", 7));

    /** An entry on the operator stack: an operator, an open paren, or a function waiting for its ')' . */
    private record Pending(String kind, String text, int pos) {
        boolean isParen() {
            return kind.equals("paren");
        }

        boolean isFunction() {
            return kind.equals("fn");
        }
    }

    /** Marks a plain grouping paren on {@link #argCounts}; compared by identity. */
    private static final int[] GROUPING = new int[0];

    private final List<Token> tokens;
    private final Deque<Expr> out = new ArrayDeque<>();
    private final Deque<Pending> ops = new ArrayDeque<>();
    /** Argument counters for the functions currently open, innermost first. */
    private final Deque<int[]> argCounts = new ArrayDeque<>();

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    static Expr parse(String src) {
        return new Parser(Lexer.tokenize(src)).run();
    }

    private Expr run() {
        boolean expectOperand = true;
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            switch (t.kind()) {
                case NUMBER -> {
                    requireOperandPosition(expectOperand, t);
                    out.push(new Expr.Num(new BigDecimal(t.text())));
                    expectOperand = false;
                }
                case IDENT -> {
                    requireOperandPosition(expectOperand, t);
                    boolean call = i + 1 < tokens.size() && tokens.get(i + 1).kind() == Token.Kind.LPAREN;
                    if (call) {
                        if (!Functions.ALLOWED.containsKey(t.text())) {
                            throw new FormulaException("formula-function",
                                    "unknown function '" + t.text() + "' at " + t.pos() + "; allowed: "
                                            + new java.util.TreeSet<>(Functions.ALLOWED.keySet()));
                        }
                        ops.push(new Pending("fn", t.text(), t.pos()));
                        ops.push(new Pending("paren", "(", tokens.get(i + 1).pos()));
                        boolean empty = i + 2 < tokens.size() && tokens.get(i + 2).kind() == Token.Kind.RPAREN;
                        argCounts.push(new int[] {empty ? 0 : 1});
                        depth = enter(depth, t);
                        i++;
                        expectOperand = true; // an immediate ')' is let through and fails the arity check
                    } else {
                        out.push(new Expr.Var(t.text()));
                        expectOperand = false;
                    }
                }
                case LPAREN -> {
                    requireOperandPosition(expectOperand, t);
                    ops.push(new Pending("paren", "(", t.pos()));
                    argCounts.push(GROUPING);
                    depth = enter(depth, t);
                }
                case COMMA -> {
                    if (expectOperand) {
                        throw syntax("missing argument before ','", t);
                    }
                    popUntilParen(t);
                    int[] count = argCounts.peek();
                    if (count == GROUPING) {
                        throw syntax("',' outside a function call", t);
                    }
                    count[0]++;
                    expectOperand = true;
                }
                case RPAREN -> {
                    int[] count = argCounts.isEmpty() ? null : argCounts.peek();
                    boolean zeroArgCall = count != null && count != GROUPING && count[0] == 0;
                    if (expectOperand && !zeroArgCall) {
                        throw syntax("missing operand before ')'", t);
                    }
                    popUntilParen(t);
                    ops.pop();
                    argCounts.pop();
                    depth--;
                    if (!ops.isEmpty() && ops.peek().isFunction()) {
                        Pending fn = ops.pop();
                        reduceCall(fn, count[0]);
                    }
                    expectOperand = false;
                }
                case OP -> {
                    String op = t.text();
                    if (expectOperand) {
                        if (!op.equals("-")) {
                            throw syntax("operator '" + op + "' needs a left operand", t);
                        }
                        op = "neg";
                    } else {
                        while (!ops.isEmpty() && !ops.peek().isParen() && !ops.peek().isFunction()
                                && PRECEDENCE.get(ops.peek().text()) >= PRECEDENCE.get(op)) {
                            reduceOp(ops.pop());
                        }
                    }
                    ops.push(new Pending("op", op, t.pos()));
                    expectOperand = true;
                }
                default -> throw new IllegalStateException();
            }
        }
        if (expectOperand) {
            throw new FormulaException("formula-syntax", "formula ends where an operand was expected");
        }
        while (!ops.isEmpty()) {
            Pending p = ops.pop();
            if (p.isParen()) {
                throw new FormulaException("formula-syntax", "unclosed '(' at " + p.pos());
            }
            reduceOp(p);
        }
        if (out.size() != 1) {
            throw new FormulaException("formula-syntax", "formula does not reduce to one expression");
        }
        return out.pop();
    }

    private static int enter(int depth, Token t) {
        if (depth + 1 > MAX_DEPTH) {
            throw syntax("nesting deeper than " + MAX_DEPTH, t);
        }
        return depth + 1;
    }

    private void popUntilParen(Token at) {
        while (!ops.isEmpty() && !ops.peek().isParen()) {
            reduceOp(ops.pop());
        }
        if (ops.isEmpty()) {
            throw syntax("unbalanced ')' or ','", at);
        }
    }

    private void reduceOp(Pending p) {
        if (p.text().equals("neg")) {
            out.push(new Expr.Op("neg", List.of(out.pop())));
            return;
        }
        Expr right = out.pop();
        Expr left = out.pop();
        out.push(new Expr.Op(p.text(), List.of(left, right)));
    }

    private void reduceCall(Pending fn, int n) {
        var arity = Functions.ALLOWED.get(fn.text());
        if (n < arity.min() || n > arity.max()) {
            throw new FormulaException("formula-arity", fn.text() + "() takes " + (arity.min() == arity.max()
                    ? arity.min() : arity.min() + ".." + arity.max()) + " arguments, got " + n + " at " + fn.pos());
        }
        var args = new ArrayList<Expr>(n);
        for (int k = 0; k < n; k++) {
            args.addFirst(out.pop());
        }
        out.push(new Expr.Call(fn.text(), List.copyOf(args)));
    }

    private static void requireOperandPosition(boolean expectOperand, Token t) {
        if (!expectOperand) {
            throw syntax("unexpected '" + t.text() + "'", t);
        }
    }

    private static FormulaException syntax(String msg, Token t) {
        return new FormulaException("formula-syntax", msg + " at " + t.pos());
    }
}
