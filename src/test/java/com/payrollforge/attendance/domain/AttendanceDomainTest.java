package com.payrollforge.attendance.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.payrollforge.attendance.domain.PunchSource.DEVICE;
import static com.payrollforge.attendance.domain.PunchSource.MOBILE;
import static org.assertj.core.api.Assertions.assertThat;

class AttendanceDomainTest {

    static Punch p(String hhmmss, PunchSource s) {
        return new Punch(Instant.parse("2026-05-04T" + hhmmss + "Z"), s);
    }

    static final DayClassifier.Shift SHIFT = new DayClassifier.Shift(480, 240);

    @Test
    void pairs_punches_debounces_and_merges_overlapping_sources() {
        var r = WorkedTime.of(List.of(
                p("09:00:00", DEVICE), p("09:00:30", DEVICE), // double tap within 60 s is one punch
                p("13:00:00", DEVICE), p("14:00:00", DEVICE), p("18:00:00", DEVICE),
                p("12:30:00", MOBILE), p("14:30:00", MOBILE))); // overlaps both device intervals
        assertThat(r.missingPunch()).isFalse();
        assertThat(r.intervals()).hasSize(1);
        assertThat(r.minutes()).isEqualTo(9 * 60);
    }

    @Test
    void odd_punch_count_is_flagged_and_the_last_punch_ignored() {
        var r = WorkedTime.of(List.of(p("09:00:00", DEVICE), p("13:00:00", DEVICE), p("14:00:00", DEVICE)));
        assertThat(r.missingPunch()).isTrue();
        assertThat(r.minutes()).isEqualTo(240);
    }

    @Test
    void interval_merge_keeps_disjoint_intervals_apart() {
        var a = new WorkedTime.Interval(Instant.parse("2026-05-04T09:00:00Z"), Instant.parse("2026-05-04T10:00:00Z"));
        var b = new WorkedTime.Interval(Instant.parse("2026-05-04T10:00:00Z"), Instant.parse("2026-05-04T11:00:00Z"));
        var c = new WorkedTime.Interval(Instant.parse("2026-05-04T12:00:00Z"), Instant.parse("2026-05-04T12:30:00Z"));
        assertThat(WorkedTime.merge(List.of(c, b, a))).hasSize(2);
    }

    @Test
    void geofence_uses_great_circle_distance() {
        // about 0.001 degrees of latitude is ~111 m
        double d = Geofence.distanceMetres(12.9716, 77.5946, 12.9726, 77.5946);
        assertThat(d).isBetween(110.0, 112.5);
        assertThat(Geofence.inside(12.9726, 77.5946, 12.9716, 77.5946, 150)).isTrue();
        assertThat(Geofence.inside(12.9756, 77.5946, 12.9716, 77.5946, 150)).isFalse();
    }

    @Test
    void day_classification_rules() {
        assertThat(DayClassifier.classify(true, true, null, 0, SHIFT).status()).isEqualTo(DayStatus.HOLIDAY);
        assertThat(DayClassifier.classify(true, false, null, 0, SHIFT).payable()).isEqualByComparingTo("1");
        assertThat(DayClassifier.classify(false, false, null, 480, SHIFT).status()).isEqualTo(DayStatus.PRESENT);
        assertThat(DayClassifier.classify(false, false, null, 300, SHIFT).payable()).isEqualByComparingTo("0.5");
        assertThat(DayClassifier.classify(false, false, null, 100, SHIFT).status()).isEqualTo(DayStatus.ABSENT);
        var unpaid = new DayClassifier.LeaveOnDay(false, false);
        assertThat(DayClassifier.classify(false, false, unpaid, 0, SHIFT).payable()).isEqualByComparingTo("0");
        var halfPaid = new DayClassifier.LeaveOnDay(true, true);
        assertThat(DayClassifier.classify(false, false, halfPaid, 250, SHIFT).payable()).isEqualByComparingTo("1");
        assertThat(DayClassifier.classify(false, false, halfPaid, 10, SHIFT).payable()).isEqualByComparingTo("0.5");
    }
}
