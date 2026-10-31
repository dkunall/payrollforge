package com.payrollforge.employee.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimelineTest {

    static final UUID ID = UUID.randomUUID();

    static EmployeeVersion v(String from, String ctc) {
        return new EmployeeVersion(ID, LocalDate.parse(from), null, "G1", "ENG", "BLR", null, new BigDecimal(ctc), "STD");
    }

    @Test
    void derives_end_dates_and_answers_as_of_queries() {
        var t = new Timeline(List.of(v("2026-05-10", "1500000"), v("2026-01-01", "1200000")));
        assertThat(t.versions().getFirst().effectiveTo()).isEqualTo(LocalDate.parse("2026-05-09"));
        assertThat(t.versions().getLast().effectiveTo()).isNull();
        assertThat(t.asOf(LocalDate.parse("2026-05-09")).orElseThrow().ctcAnnual()).isEqualByComparingTo("1200000");
        assertThat(t.asOf(LocalDate.parse("2026-05-10")).orElseThrow().ctcAnnual()).isEqualByComparingTo("1500000");
        assertThat(t.asOf(LocalDate.parse("2025-12-31"))).isEmpty();
    }

    @Test
    void segments_clip_to_the_period() {
        var t = new Timeline(List.of(v("2026-01-01", "1200000"), v("2026-05-10", "1500000")));
        var segs = t.segments(LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-31"));
        assertThat(segs).extracting(Timeline.Segment::days).containsExactly(9L, 22L);
        var joiner = new Timeline(List.of(v("2026-05-21", "900000")));
        assertThat(joiner.segments(LocalDate.parse("2026-05-01"), LocalDate.parse("2026-05-31")))
                .singleElement().satisfies(s -> assertThat(s.from()).isEqualTo(LocalDate.parse("2026-05-21")));
    }

    @Test
    void two_versions_cannot_start_on_one_day() {
        assertThatThrownBy(() -> new Timeline(List.of(v("2026-01-01", "1"), v("2026-01-01", "2"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
