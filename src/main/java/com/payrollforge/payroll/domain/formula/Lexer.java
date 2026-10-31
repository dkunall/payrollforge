package com.payrollforge.payroll.domain.formula;

import java.util.ArrayList;
import java.util.List;

/** Splits a formula into tokens. Anything outside the small grammar is a syntax error, never a pass-through. */
final class Lexer {

    static final int MAX_LENGTH = 500;

    private Lexer() {
    }

    static List<Token> tokenize(String src) {
        if (src == null || src.isBlank()) {
            throw new FormulaException("formula-syntax", "formula is empty");
        }
        if (src.length() > MAX_LENGTH) {
            throw new FormulaException("formula-syntax", "formula is longer than " + MAX_LENGTH + " characters");
        }
        var out = new ArrayList<Token>();
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < src.length() && Character.isDigit(src.charAt(i + 1)))) {
                int start = i;
                boolean dot = false;
                while (i < src.length() && (Character.isDigit(src.charAt(i)) || (src.charAt(i) == '.' && !dot))) {
                    dot |= src.charAt(i) == '.';
                    i++;
                }
                if (i - start > 20) {
                    throw new FormulaException("formula-syntax", "number too long at " + start);
                }
                out.add(new Token(Token.Kind.NUMBER, src.substring(start, i), start));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < src.length() && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) {
                    i++;
                }
                out.add(new Token(Token.Kind.IDENT, src.substring(start, i), start));
            } else if (c == '(') {
                out.add(new Token(Token.Kind.LPAREN, "(", i++));
            } else if (c == ')') {
                out.add(new Token(Token.Kind.RPAREN, ")", i++));
            } else if (c == ',') {
                out.add(new Token(Token.Kind.COMMA, ",", i++));
            } else {
                String two = i + 1 < src.length() ? src.substring(i, i + 2) : "";
                if (two.equals("<=") || two.equals(">=") || two.equals("==") || two.equals("!=")
                        || two.equals("&&") || two.equals("||")) {
                    out.add(new Token(Token.Kind.OP, two, i));
                    i += 2;
                } else if ("+-*/<>".indexOf(c) >= 0) {
                    out.add(new Token(Token.Kind.OP, String.valueOf(c), i++));
                } else {
                    throw new FormulaException("formula-syntax", "unexpected '" + c + "' at " + i);
                }
            }
        }
        return out;
    }
}
