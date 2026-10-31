package com.payrollforge.payroll.domain.run;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Explains why net pay moved between two runs. Both sides are indexed by employee in hash maps, then each
 * employee's lines and inputs are compared key by key. The explanation names the inputs that changed, since
 * those are what an approver can check against HR records.
 */
public final class RunDiff {

    /** One side of the comparison for one employee. */
    public record Side(String code, String name, BigDecimal net, Map<String, BigDecimal> lines, Map<String, String> inputs) {
    }

    public record Change(String key, String from, String to, BigDecimal delta) {
    }

    public record EmployeeDiff(UUID employeeId, String code, String name, String kind, BigDecimal netFrom,
                               BigDecimal netTo, BigDecimal netDelta, List<Change> lines, List<Change> inputs,
                               boolean outlier, String explanation) {
    }

    public record Summary(int compared, int changed, int added, int removed, int outliers, BigDecimal netFrom,
                          BigDecimal netTo, List<EmployeeDiff> employees) {
    }

    private RunDiff() {
    }

    /** @param outlierRatio a net change above this share of the old net (0.2 = 20%) is flagged */
    public static Summary between(Map<UUID, Side> base, Map<UUID, Side> other, BigDecimal outlierRatio) {
        var ids = new TreeSet<UUID>(base.keySet());
        ids.addAll(other.keySet());
        var out = new ArrayList<EmployeeDiff>();
        int changed = 0;
        int added = 0;
        int removed = 0;
        int outliers = 0;
        BigDecimal totalFrom = BigDecimal.ZERO.setScale(2);
        BigDecimal totalTo = BigDecimal.ZERO.setScale(2);
        for (UUID id : ids) {
            Side a = base.get(id);
            Side b = other.get(id);
            totalFrom = totalFrom.add(a == null ? BigDecimal.ZERO : a.net());
            totalTo = totalTo.add(b == null ? BigDecimal.ZERO : b.net());
            if (a == null || b == null) {
                Side s = a == null ? b : a;
                String kind = a == null ? "ADDED" : "REMOVED";
                if (a == null) {
                    added++;
                } else {
                    removed++;
                }
                outliers++;
                BigDecimal from = a == null ? BigDecimal.ZERO.setScale(2) : a.net();
                BigDecimal to = b == null ? BigDecimal.ZERO.setScale(2) : b.net();
                out.add(new EmployeeDiff(id, s.code(), s.name(), kind, from, to, to.subtract(from), List.of(),
                        List.of(), true, a == null ? "not in the earlier run (joined or newly paid)"
                        : "not in this run (left or not paid)"));
                continue;
            }
            var lines = changes(toStrings(a.lines()), toStrings(b.lines()), true);
            var inputs = changes(a.inputs(), b.inputs(), false);
            BigDecimal delta = b.net().subtract(a.net());
            if (delta.signum() == 0 && lines.isEmpty() && inputs.isEmpty()) {
                continue;
            }
            changed++;
            boolean outlier = isOutlier(a.net(), delta, outlierRatio);
            if (outlier) {
                outliers++;
            }
            out.add(new EmployeeDiff(id, a.code(), a.name(), "CHANGED", a.net(), b.net(), delta, lines, inputs,
                    outlier, explain(delta, lines, inputs)));
        }
        return new Summary(ids.size(), changed, added, removed, outliers, totalFrom, totalTo, List.copyOf(out));
    }

    private static boolean isOutlier(BigDecimal from, BigDecimal delta, BigDecimal ratio) {
        if (from.signum() == 0) {
            return delta.signum() != 0;
        }
        return delta.abs().divide(from.abs(), 6, RoundingMode.HALF_UP).compareTo(ratio) > 0;
    }

    private static Map<String, String> toStrings(Map<String, BigDecimal> m) {
        var out = new HashMap<String, String>();
        m.forEach((k, v) -> out.put(k, v.toPlainString()));
        return out;
    }

    private static List<Change> changes(Map<String, String> a, Map<String, String> b, boolean money) {
        var keys = new TreeSet<String>(a.keySet());
        keys.addAll(b.keySet());
        var out = new ArrayList<Change>();
        for (String k : keys) {
            String x = a.get(k);
            String y = b.get(k);
            if (same(x, y)) {
                continue;
            }
            BigDecimal delta = null;
            if (isNumber(x) || isNumber(y)) {
                delta = num(y).subtract(num(x));
                if (money) {
                    delta = delta.setScale(2, RoundingMode.HALF_UP);
                }
            }
            out.add(new Change(k, x, y, delta));
        }
        return out;
    }

    private static boolean same(String x, String y) {
        if (isNumber(x) && isNumber(y)) {
            return new BigDecimal(x).compareTo(new BigDecimal(y)) == 0;
        }
        return Objects.equals(x, y);
    }

    private static boolean isNumber(String s) {
        if (s == null) {
            return false;
        }
        try {
            new BigDecimal(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static BigDecimal num(String s) {
        return isNumber(s) ? new BigDecimal(s) : BigDecimal.ZERO;
    }

    private static String explain(BigDecimal delta, List<Change> lines, List<Change> inputs) {
        var sb = new StringBuilder();
        sb.append("net ").append(delta.signum() >= 0 ? "+" : "").append(delta.toPlainString());
        if (!inputs.isEmpty()) {
            sb.append("; inputs changed: ");
            sb.append(String.join(", ", inputs.stream().map(c -> c.key() + " " + nz(c.from()) + " -> " + nz(c.to())).toList()));
        }
        var biggest = lines.stream().filter(c -> c.delta() != null)
                .sorted((p, q) -> q.delta().abs().compareTo(p.delta().abs())).limit(3).toList();
        if (!biggest.isEmpty()) {
            sb.append("; largest line moves: ");
            sb.append(String.join(", ", biggest.stream().map(c -> c.key() + " " + (c.delta().signum() >= 0 ? "+" : "")
                    + c.delta().toPlainString()).toList()));
        }
        if (inputs.isEmpty()) {
            sb.append("; no input changed (structure or tax rules differ)");
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "none" : s;
    }
}
