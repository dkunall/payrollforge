package com.payrollforge.attendance.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns one day's punches into worked minutes. Within each source, punches are sorted, repeats closer than
 * {@link #DEBOUNCE} are dropped (a double tap is one punch), and the rest are paired in/out. The intervals from
 * all sources are then merged, so walking in at the door and also punching on the phone is not counted twice.
 * An odd punch count leaves the last one unpaired and flags a missing punch.
 */
public final class WorkedTime {

    public static final Duration DEBOUNCE = Duration.ofSeconds(60);

    public record Interval(Instant from, Instant to) {
    }

    public record Result(long minutes, boolean missingPunch, List<Interval> intervals) {
    }

    private WorkedTime() {
    }

    public static Result of(List<Punch> punches) {
        Map<PunchSource, List<Instant>> bySource = punches.stream().collect(Collectors.groupingBy(Punch::source,
                Collectors.mapping(Punch::at, Collectors.toList())));
        var intervals = new ArrayList<Interval>();
        boolean missing = false;
        for (var times : bySource.values()) {
            var sorted = new ArrayList<>(times);
            sorted.sort(Comparator.naturalOrder());
            var kept = new ArrayList<Instant>();
            for (Instant t : sorted) {
                if (kept.isEmpty() || Duration.between(kept.getLast(), t).compareTo(DEBOUNCE) >= 0) {
                    kept.add(t);
                }
            }
            for (int i = 0; i + 1 < kept.size(); i += 2) {
                intervals.add(new Interval(kept.get(i), kept.get(i + 1)));
            }
            missing |= kept.size() % 2 == 1;
        }
        var merged = merge(intervals);
        long seconds = merged.stream().mapToLong(iv -> Duration.between(iv.from(), iv.to()).toSeconds()).sum();
        return new Result(seconds / 60, missing, merged);
    }

    /** Classic interval merge: sort by start, extend the current interval while the next one overlaps it. */
    public static List<Interval> merge(List<Interval> in) {
        var sorted = new ArrayList<>(in);
        sorted.sort(Comparator.comparing(Interval::from));
        var out = new ArrayList<Interval>();
        for (Interval iv : sorted) {
            if (!out.isEmpty() && !iv.from().isAfter(out.getLast().to())) {
                Interval last = out.removeLast();
                out.add(new Interval(last.from(), iv.to().isAfter(last.to()) ? iv.to() : last.to()));
            } else {
                out.add(iv);
            }
        }
        return out;
    }
}
