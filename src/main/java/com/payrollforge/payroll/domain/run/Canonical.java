package com.payrollforge.payroll.domain.run;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * A canonical JSON form for hashing: object keys sorted, no whitespace, numbers written as plain decimal strings.
 * Writing it here (not with Jackson's settings) keeps the hash independent of library defaults.
 */
public final class Canonical {

    private Canonical() {
    }

    public static String json(Object v) {
        var sb = new StringBuilder();
        write(v, sb);
        return sb.toString();
    }

    public static String sha256(Object v) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(json(v).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(Object v, StringBuilder sb) {
        switch (v) {
            case null -> sb.append("null");
            case Map<?, ?> m -> {
                var sorted = new TreeMap<String, Object>();
                m.forEach((k, val) -> sorted.put(String.valueOf(k), val));
                sb.append('{');
                boolean first = true;
                for (var e : sorted.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    string(e.getKey(), sb);
                    sb.append(':');
                    write(e.getValue(), sb);
                }
                sb.append('}');
            }
            case Collection<?> c -> {
                sb.append('[');
                boolean first = true;
                for (Object o : c) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    write(o, sb);
                }
                sb.append(']');
            }
            case Boolean b -> sb.append(b);
            case BigDecimal d -> string(d.toPlainString(), sb);
            case Number n -> string(new BigDecimal(n.toString()).toPlainString(), sb);
            default -> string(v.toString(), sb);
        }
    }

    private static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
