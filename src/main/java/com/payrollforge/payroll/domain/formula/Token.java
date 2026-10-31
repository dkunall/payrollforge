package com.payrollforge.payroll.domain.formula;

record Token(Kind kind, String text, int pos) {

    enum Kind { NUMBER, IDENT, OP, LPAREN, RPAREN, COMMA }

    boolean is(Kind k, String t) {
        return kind == k && text.equals(t);
    }
}
