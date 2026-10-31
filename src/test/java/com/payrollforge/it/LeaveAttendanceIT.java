package com.payrollforge.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.payrollforge.support.Api;
import com.payrollforge.support.ItBase;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Leave, claims and punches under concurrency. Dates are relative to today so the punch age limit holds. */
class LeaveAttendanceIT extends ItBase {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void leaveBalanceNeverGoesNegativeUnderConcurrentRequests() throws Exception {
        Person p = employee(LocalDate.of(2026, 1, 1), "600000.00");
        hr().post("/v1/employees/" + p.id() + "/leave-grants", Map.of("type", "CASUAL", "days", 3, "ref", "it-" + p.code()))
                .expect(200);
        List<LocalDate> days = workingDays(LocalDate.now(IST).plusDays(40), 10);

        List<Api.Response> applied = PayrollRunIT.race(10, i -> p.api().post("/v1/leaves", Map.of("type", "CASUAL",
                "from", days.get(i).toString(), "to", days.get(i).toString(), "reason", "personal")));

        assertThat(applied).filteredOn(r -> r.status() == 201).hasSize(3);
        assertThat(applied).filteredOn(r -> r.status() == 409).hasSize(7)
                .allSatisfy(r -> assertThat(r.text("code")).isEqualTo("insufficient-balance"));
        assertThat(balance(p, "CASUAL")).isEqualByComparingTo("0");
        assertThat(db.sql("select coalesce(sum(days), 0) from leave_ledger where employee_id = ?::uuid and leave_type = 'CASUAL'")
                .param(p.id()).query(BigDecimal.class).single()).isEqualByComparingTo("0");

        // a rejected request refunds its day exactly once, even when HR clicks reject many times at once
        String id = applied.stream().filter(r -> r.status() == 201).findFirst().orElseThrow()
                .body().path("leave").path("id").asText();
        List<Api.Response> rejects = PayrollRunIT.race(6, i -> hr().post("/v1/leaves/" + id + "/reject", Map.of()));
        assertThat(statuses(rejects)).containsExactly(200, 409, 409, 409, 409, 409);
        assertThat(balance(p, "CASUAL")).isEqualByComparingTo("1");

        // overlapping dates are refused
        String taken = applied.stream().filter(r -> r.status() == 201).skip(1).findFirst().orElseThrow()
                .body().path("leave").path("from").asText();
        assertThat(p.api().post("/v1/leaves", Map.of("type", "LOP", "from", taken, "to", taken)).expect(409)
                .text("code")).isEqualTo("leave-overlap");
    }

    @Test
    void approvalsAreDecidedOnceAndOnlyByTheAssignee() throws Exception {
        Person boss = employee(LocalDate.of(2026, 1, 1), "2400000.00");
        Person p = employee(LocalDate.of(2026, 1, 1), "600000.00", "STD", boss.id());
        Person other = employee(LocalDate.of(2026, 1, 1), "600000.00");
        JsonNode claim = p.api().post("/v1/reimbursements", Map.of("category", "INTERNET",
                "amount", new BigDecimal("1499.99"), "spentOn", LocalDate.now(IST).minusDays(3).toString()))
                .expect(201).body();
        assertThat(claim.path("amount").decimalValue()).isEqualByComparingTo("1499.99");
        String id = claim.path("id").asText();

        other.api().post("/v1/reimbursements/" + id + "/approve", Map.of()).expect(403);
        p.api().post("/v1/reimbursements/" + id + "/approve", Map.of()).expect(403);
        List<Api.Response> votes = PayrollRunIT.race(6, i -> boss.api().post("/v1/reimbursements/" + id + "/approve",
                Map.of()));
        assertThat(statuses(votes)).containsExactly(200, 409, 409, 409, 409, 409);
        assertThat(count("select count(*) from approval_step where subject_id = ?::uuid and status = 'APPROVED'", id))
                .isEqualTo(1);
        assertThat(p.api().get("/v1/reimbursements/" + id).expect(200).text("status")).isEqualTo("APPROVED");
    }

    @Test
    void duplicatePunchesAreStoredOnceAndAClosedDayIsDecided() throws Exception {
        Person p = employee(LocalDate.of(2026, 1, 1), "600000.00");
        LocalDate day = lastWorkingDayBefore(LocalDate.now(IST).minusDays(1));
        String in = ZonedDateTime.of(day.atTime(9, 2), IST).toInstant().toString();
        String out = ZonedDateTime.of(day.atTime(18, 10), IST).toInstant().toString();
        var batch = Map.of("punches", List.of(
                Map.of("deviceEventId", "dev-" + p.code() + "-in", "employeeId", p.id(), "at", in, "source", "DEVICE"),
                Map.of("deviceEventId", "dev-" + p.code() + "-out", "employeeId", p.id(), "at", out, "source", "DEVICE")));

        // the terminal retries the same batch from several threads
        List<Api.Response> sent = PayrollRunIT.race(6, i -> as(DEVICE).post("/v1/attendance/punches:batch", batch));
        long accepted = 0;
        for (var r : sent) {
            r.expect(200);
            for (JsonNode x : r.body()) {
                assertThat(x.path("outcome").asText()).isIn("ACCEPTED", "DUPLICATE");
                accepted += x.path("outcome").asText().equals("ACCEPTED") ? 1 : 0;
            }
        }
        assertThat(accepted).isEqualTo(2);
        assertThat(count("select count(*) from attendance_punch where employee_id = ?::uuid", p.id())).isEqualTo(2);

        // a phone punch has to be the caller's own and inside the office geofence
        JsonNode far = p.api().post("/v1/attendance/punches:batch", Map.of("punches", List.of(Map.of(
                "deviceEventId", "mob-" + p.code(), "employeeId", p.id(), "at", in, "source", "MOBILE",
                "lat", 13.0827, "lng", 80.2707)))).expect(200).body();
        assertThat(far.get(0).path("outcome").asText()).isEqualTo("REJECTED");
        assertThat(far.get(0).path("reason").asText()).contains("geofence");
        JsonNode forged = p.api().post("/v1/attendance/punches:batch", Map.of("punches", List.of(Map.of(
                "deviceEventId", "dev-" + p.code() + "-forged", "employeeId", p.id(), "at", in, "source", "DEVICE"))))
                .expect(200).body();
        assertThat(forged.get(0).path("outcome").asText()).isEqualTo("REJECTED");

        hr().post("/v1/attendance/days:close", Map.of("day", day.toString())).expect(200);
        JsonNode closed = hr().get("/v1/attendance/days?employeeId=" + p.id() + "&from=" + day + "&to=" + day)
                .expect(200).body().get(0);
        assertThat(closed.path("status").asText()).isEqualTo("PRESENT");
        assertThat(closed.path("payable").decimalValue()).isEqualByComparingTo("1");
        assertThat(closed.path("workedMinutes").asInt()).isEqualTo(9 * 60 + 8);
        // payroll sees the same day through the outbox
        eventually(() -> count("select count(*) from payroll_day where employee_id = ?::uuid and day = ?::date"
                + " and payable = 1", p.id(), day.toString()) == 1);
    }

    private static List<Integer> statuses(List<Api.Response> rs) {
        return rs.stream().map(r -> r.status() == 200 || r.status() == 409 ? r.status() : -r.status()).sorted().toList();
    }

    private BigDecimal balance(Person p, String type) {
        return db.sql("select balance from leave_balance where employee_id = ?::uuid and leave_type = ?")
                .params(p.id(), type).query(BigDecimal.class).single();
    }

    private static List<LocalDate> workingDays(LocalDate from, int n) {
        var out = new ArrayList<LocalDate>();
        for (LocalDate d = from; out.size() < n; d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                out.add(d);
            }
        }
        return out;
    }

    private static LocalDate lastWorkingDayBefore(LocalDate d) {
        while (d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }
}
