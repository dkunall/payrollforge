package com.payrollforge.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.payrollforge.support.Api;
import com.payrollforge.support.ItBase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * One payroll story told month by month. Runs share one entity and periods must move forward, so the
 * months are fixed (2026-01 to 2026-04) and the methods run in order. Assertions look only at the
 * employees created here; other test classes add people to the same runs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PayrollRunIT extends ItBase {

    private static final String RUNS = "/v1/payroll-runs";

    Person steady;   // full months; gets a backdated raise in March
    Person joiner;   // joins mid-January
    Person intern;   // INTERN structure
    String jan;
    String feb;

    @Test
    @Order(1)
    void createIsExactlyOncePerKeyAndPerPeriod() throws Exception {
        steady = employee(LocalDate.of(2025, 12, 1), "1200000.00");
        joiner = employee(LocalDate.of(2026, 1, 15), "900000.00");
        intern = employee(LocalDate.of(2025, 12, 1), "240000.00", "INTERN", null);
        for (int k = 0; k < 6; k++) { // enough people for several chunks of four
            employee(LocalDate.of(2025, 12, 1), (600000 + 50000 * k) + ".00");
        }

        // different keys racing for one period: one run, the rest are told one is open
        List<Api.Response> racing = race(8, i -> maker().post(RUNS, Map.of("period", "2026-01"),
                Map.of("Idempotency-Key", "jan-race-" + i)));
        assertThat(racing).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(racing).filteredOn(r -> r.status() == 409)
                .hasSize(7).allSatisfy(r -> assertThat(r.text("code")).isEqualTo("open-run-exists"));
        String first = racing.stream().filter(r -> r.status() == 201).findFirst().orElseThrow().text("id");
        maker().post(RUNS + "/" + first + "/cancel", Map.of()).expect(200);

        // one key retried concurrently: one 201, every retry gets the same run back
        List<Api.Response> retries = race(8, i -> maker().post(RUNS, Map.of("period", "2026-01"),
                Map.of("Idempotency-Key", "jan-final")));
        assertThat(retries).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(retries).filteredOn(r -> r.status() == 200).hasSize(7);
        assertThat(retries).extracting(r -> r.text("id")).containsOnly(retries.get(0).text("id"));
        jan = retries.get(0).text("id");
        assertThat(count("select count(*) from payroll_run where period = '2026-01' and status <> 'CANCELLED'"))
                .isEqualTo(1);

        // the same key for another period is a client bug, not a retry
        maker().post(RUNS, Map.of("period", "2026-02"), Map.of("Idempotency-Key", "jan-final")).expect(422);
    }

    @Test
    @Order(2)
    void januaryComputesExactMoneyAndSettlesWithFourEyes() {
        // HR closes one January working day; nobody here punched, so it is an unpaid absence
        hr().post("/v1/attendance/days:close", Map.of("day", "2026-01-20")).expect(200);
        eventually(() -> count("select count(*) from payroll_day where employee_id = ?::uuid and day = '2026-01-20'"
                + " and payable = 0", steady.id()) == 1);

        maker().post(RUNS + "/" + jan + "/start", Map.of()).expect(202);
        awaitStatus(jan, "REVIEW");

        JsonNode s = slip(jan, steady);
        assertThat(input(s, "PERIOD_DAYS")).isEqualByComparingTo("31");
        assertThat(input(s, "PAYABLE_DAYS")).isEqualByComparingTo("30");
        assertThat(input(s, "LOP_DAYS")).isEqualByComparingTo("1");
        assertThat(line(s, "BASIC")).isEqualByComparingTo("38710"); // round(100000 * 0.40 * 30/31)
        JsonNode j = slip(jan, joiner);
        assertThat(input(j, "PAYABLE_DAYS")).isEqualByComparingTo("16"); // 15..31 minus the 20th
        JsonNode i = slip(jan, intern);
        assertThat(line(i, "STIPEND")).isEqualByComparingTo("19354.84"); // 20000 * 30 / 31, two places
        for (JsonNode p : List.of(s, j, i)) {
            assertMoneyAddsUp(p);
        }

        // submit, then approval needs a checker who is not the submitter
        maker().post(RUNS + "/" + jan + "/submit", Map.of()).expect(200);
        maker().post(RUNS + "/" + jan + "/approve", Map.of()).expect(403);
        checker().post(RUNS + "/" + jan + "/approve", Map.of()).expect(200);

        // the month is locked now: no more closing days in it
        assertThat(hr().post("/v1/attendance/days:close", Map.of("day", "2026-01-21")).status()).isIn(409, 422);

        checker().post(RUNS + "/" + jan + "/finalize", Map.of()).expect(200);
        JsonNode verify = maker().post(RUNS + "/" + jan + ":verify", Map.of()).expect(200).body();
        assertThat(verify.path("matched").asInt()).isEqualTo(verify.path("employees").asInt());
        assertThat(verify.path("mismatches")).isEmpty();
        assertThat(verify.path("snapshotHashesOk").asBoolean()).isTrue();
        assertThat(verify.path("runHashOk").asBoolean()).isTrue();

        // register total is the exact sum of every net pay
        String csv = maker().get(RUNS + "/" + jan + "/register.csv").expect(200).body().asText();
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode p : maker().get(RUNS + "/" + jan + "/payslips").expect(200).body()) {
            sum = sum.add(p.path("net").decimalValue());
        }
        String[] lines = csv.split("\n");
        String total = lines[lines.length - 1];
        assertThat(total).startsWith("TOTAL,");
        assertThat(new BigDecimal(total.substring(total.lastIndexOf(',') + 1))).isEqualByComparingTo(sum);
        assertThat(lines.length - 2).isEqualTo(verify.path("employees").asInt());

        // the PDF renders in the background; the employee sees the payslip once the run is settled
        eventually(() -> slip(jan, steady).path("pdfReady").asBoolean());
        var pdf = maker().get(RUNS + "/" + jan + "/payslips/" + steady.id() + "/pdf").expect(200);
        assertThat(pdf.header("Content-Type")).startsWith("application/pdf");
        assertThat(pdf.body().asText()).startsWith("%PDF");
        assertThat(steady.api().get("/v1/me/payslips").expect(200).body().findValuesAsText("runId")).contains(jan);
        steady.api().get(RUNS + "/" + jan + "/payslips/" + steady.id()).expect(200);
        steady.api().get(RUNS + "/" + jan + "/payslips/" + joiner.id()).expect(403);
        steady.api().get(RUNS + "/" + jan + "/register.csv").expect(403);
    }

    @Test
    @Order(3)
    void bankFileIsGeneratedOnceAndReissueNeedsAnotherPerson() throws Exception {
        List<Api.Response> gens = race(8, k -> maker().post(RUNS + "/" + jan + "/bank-file", Map.of()));
        assertThat(gens).allSatisfy(r -> assertThat(r.status()).isIn(200, 201));
        assertThat(gens).extracting(r -> r.text("id")).containsOnly(gens.get(0).text("id"));
        assertThat(count("select count(*) from bank_file where run_id = ?::uuid", jan)).isEqualTo(1);

        JsonNode file = gens.get(0).body();
        String content = checker().get(RUNS + "/" + jan + "/bank-file/content").expect(200).body().asText();
        assertThat(sha256(content)).isEqualTo(file.path("sha256").asText());
        BigDecimal sum = BigDecimal.ZERO;
        String[] rows = content.split("\n");
        assertThat(rows[0]).isEqualTo("batch_ref,employee_code,beneficiary,account,ifsc,amount");
        for (int r = 1; r < rows.length; r++) {
            sum = sum.add(new BigDecimal(rows[r].substring(rows[r].lastIndexOf(',') + 1)));
        }
        assertThat(sum).isEqualByComparingTo(file.path("total").decimalValue());
        assertThat(rows.length - 1).isEqualTo(file.path("rows").asInt());
        BigDecimal positiveNets = BigDecimal.ZERO;
        for (JsonNode p : maker().get(RUNS + "/" + jan + "/payslips").body()) {
            if (p.path("net").decimalValue().signum() > 0) {
                positiveNets = positiveNets.add(p.path("net").decimalValue());
            }
        }
        assertThat(sum).isEqualByComparingTo(positiveNets);
        assertThat(content).contains(steady.code()).doesNotContain("ABCDE");

        // reissue: maker asks, a second ask waits, the same person cannot approve, a checker can
        String rid = maker().post(RUNS + "/" + jan + "/bank-file/reissues", Map.of("reason", "bank rejected the batch"))
                .expect(201).text("id");
        assertThat(maker().post(RUNS + "/" + jan + "/bank-file/reissues", Map.of("reason", "again"))
                .expect(409).text("code")).isEqualTo("reissue-pending");
        as(CHECKER_2).post(RUNS + "/" + jan + "/bank-file/reissues/" + rid + "/approve", Map.of()).expect(200);
        JsonNode reissued = maker().get(RUNS + "/" + jan + "/bank-file").expect(200).body();
        assertThat(reissued.path("batchRef").asText()).isNotEqualTo(file.path("batchRef").asText());
        assertThat(reissued.path("total").decimalValue()).isEqualByComparingTo(file.path("total").decimalValue());
        assertThat(count("select count(*) from bank_file where run_id = ?::uuid and status = 'VOID'", jan))
                .isEqualTo(1);

        checker().post(RUNS + "/" + jan + "/mark-paid", Map.of("batchRef", file.path("batchRef").asText()))
                .expect(409);
        checker().post(RUNS + "/" + jan + "/mark-paid", Map.of("batchRef", reissued.path("batchRef").asText()))
                .expect(200);
        assertThat(maker().get(RUNS + "/" + jan).body().path("run").path("status").asText()).isEqualTo("PAID");
    }

    @Test
    @Order(4)
    void aFailedChunkResumesAndChangedInputsSendTheRunBack() {
        feb = maker().post(RUNS, Map.of("period", "2026-02"), Map.of("Idempotency-Key", "feb")).expect(201).text("id");
        failOnce.armed.add(feb + ":1");
        maker().post(RUNS + "/" + feb + "/start", Map.of()).expect(202);
        eventually(() -> maker().get(RUNS + "/" + feb).body().path("chunks").path("FAILED").asLong() == 1);
        assertThat(maker().get(RUNS + "/" + feb).body().path("run").path("status").asText()).isEqualTo("COMPUTING");
        long doneBefore = count("select count(*) from payslip where run_id = ?::uuid", feb);

        maker().post(RUNS + "/" + feb + "/start", Map.of()).expect(202); // resume: only the failed chunk reruns
        awaitStatus(feb, "REVIEW");
        long employees = maker().get(RUNS + "/" + feb).body().path("run").path("employeeCount").asLong();
        assertThat(count("select count(*) from payslip where run_id = ?::uuid", feb)).isEqualTo(employees)
                .isGreaterThan(doneBefore);
        assertThat(count("select coalesce(max(attempts), 0) from payroll_chunk where run_id = ?::uuid and chunk_no <> 1",
                feb)).isEqualTo(1);

        // an input changes after compute: approval refuses and the run goes back to DRAFT
        maker().post(RUNS + "/" + feb + "/submit", Map.of()).expect(200);
        hr().post("/v1/attendance/days:close", Map.of("day", "2026-02-10")).expect(200);
        eventually(() -> count("select count(*) from payroll_day where employee_id = ?::uuid and day = '2026-02-10'",
                steady.id()) == 1);
        assertThat(checker().post(RUNS + "/" + feb + "/approve", Map.of()).expect(409).text("code"))
                .isEqualTo("inputs-changed");
        assertThat(maker().get(RUNS + "/" + feb).body().path("run").path("status").asText()).isEqualTo("DRAFT");

        maker().post(RUNS + "/" + feb + "/start", Map.of()).expect(202);
        awaitStatus(feb, "REVIEW");
        assertThat(input(slip(feb, steady), "LOP_DAYS")).isEqualByComparingTo("1");
        maker().post(RUNS + "/" + feb + "/submit", Map.of()).expect(200);
        checker().post(RUNS + "/" + feb + "/approve", Map.of()).expect(200);
        checker().post(RUNS + "/" + feb + "/finalize", Map.of()).expect(200);
    }

    @Test
    @Order(5)
    void aBackdatedRaiseIsPaidAsArrearsToTheRupee() {
        hr().post("/v1/employees/" + steady.id() + "/versions", Map.of("effectiveFrom", "2026-01-01", "grade", "L3",
                "dept", "ENG", "location", "BLR1", "ctcAnnual", new BigDecimal("1500000.00"), "structureCode", "STD",
                "reason", "annual review, backdated")).expect(201);
        // periods must move forward and only one run is open at a time
        assertThat(maker().post(RUNS, Map.of("period", "2026-02"), Map.of("Idempotency-Key", "feb-again")).status())
                .isIn(409, 422);

        String mar = maker().post(RUNS, Map.of("period", "2026-03"), Map.of("Idempotency-Key", "mar")).expect(201)
                .text("id");
        maker().post(RUNS + "/" + mar + "/start", Map.of()).expect(202);
        awaitStatus(mar, "REVIEW");
        JsonNode s = slip(mar, steady);
        assertThat(line(s, "BASIC")).isEqualByComparingTo("50000"); // 125000 * 0.40, full month
        BigDecimal janBasic = arrear(s, "2026-01", "BASIC");
        BigDecimal febBasic = arrear(s, "2026-02", "BASIC");
        assertThat(janBasic).isEqualByComparingTo("9677"); // 48387 - 38710, January had one LOP day
        assertThat(febBasic).isEqualByComparingTo("9643"); // 48214 - 38571, February had one LOP day
        BigDecimal earningArrears = BigDecimal.ZERO;
        for (JsonNode a : s.path("arrears")) {
            if (a.path("kind").asText().equals("EARNING")) {
                earningArrears = earningArrears.add(a.path("amount").decimalValue());
            }
        }
        assertThat(line(s, "ARREARS_PAY")).isEqualByComparingTo(earningArrears);
        assertMoneyAddsUp(s);
        assertThat(slip(mar, joiner).path("arrears")).isEmpty();

        // the diff against February explains the jump and flags it as an outlier
        JsonNode diff = maker().get(RUNS + "/" + mar + "/diff?against=" + feb).expect(200).body();
        JsonNode mine = find(diff.path("employees"), "code", steady.code());
        assertThat(mine.path("outlier").asBoolean()).isTrue();
        assertThat(mine.path("netDelta").decimalValue()).isPositive();

        // four-eyes is about the person: CHECKER_2 holds both roles but cannot approve their own submission
        as(CHECKER_2).post(RUNS + "/" + mar + "/submit", Map.of()).expect(200);
        assertThat(as(CHECKER_2).post(RUNS + "/" + mar + "/approve", Map.of()).expect(403).text("code"))
                .isEqualTo("four-eyes");
        maker().post(RUNS + "/" + mar + "/cancel", Map.of()).expect(200);
        assertThat(steady.api().get("/v1/me/payslips").body().findValuesAsText("runId")).doesNotContain(mar);
        steady.api().get(RUNS + "/" + mar + "/payslips/" + steady.id()).expect(404);
    }

    // --- helpers

    private void awaitStatus(String run, String status) {
        eventually(() -> maker().get(RUNS + "/" + run).body().path("run").path("status").asText().equals(status));
    }

    private JsonNode slip(String run, Person p) {
        return maker().get(RUNS + "/" + run + "/payslips/" + p.id()).expect(200).body();
    }

    private static BigDecimal input(JsonNode slip, String key) {
        return new BigDecimal(slip.path("inputs").path(key).asText());
    }

    private static BigDecimal line(JsonNode slip, String code) {
        return new BigDecimal(find(slip.path("lines"), "code", code).path("amount").asText());
    }

    private static BigDecimal arrear(JsonNode slip, String period, String component) {
        for (JsonNode a : slip.path("arrears")) {
            if (a.path("period").asText().equals(period) && a.path("component").asText().equals(component)) {
                return a.path("amount").decimalValue();
            }
        }
        throw new AssertionError("no arrear " + period + "/" + component + " in " + slip.path("arrears"));
    }

    /** Gross is the sum of earnings, deductions the sum of deductions, net the difference, all to the paisa. */
    private static void assertMoneyAddsUp(JsonNode slip) {
        BigDecimal earnings = BigDecimal.ZERO;
        BigDecimal deductions = BigDecimal.ZERO;
        for (JsonNode l : slip.path("lines")) {
            BigDecimal a = new BigDecimal(l.path("amount").asText());
            assertThat(a.scale()).as(l.path("code").asText()).isEqualTo(2);
            switch (l.path("kind").asText()) {
                case "EARNING" -> earnings = earnings.add(a);
                case "DEDUCTION" -> deductions = deductions.add(a);
                default -> { }
            }
        }
        assertThat(slip.path("gross").decimalValue()).isEqualByComparingTo(earnings);
        assertThat(slip.path("deductions").decimalValue()).isEqualByComparingTo(deductions);
        assertThat(slip.path("net").decimalValue()).isEqualByComparingTo(earnings.subtract(deductions));
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    interface Call {
        Api.Response call(int i);
    }

    static List<Api.Response> race(int n, Call call) throws Exception {
        var pool = Executors.newFixedThreadPool(n);
        try {
            var gate = new CountDownLatch(1);
            var tasks = new ArrayList<Callable<Api.Response>>();
            for (int i = 0; i < n; i++) {
                int k = i;
                tasks.add(() -> {
                    gate.await();
                    return call.call(k);
                });
            }
            var futures = tasks.stream().map(pool::submit).toList();
            gate.countDown();
            var out = new ArrayList<Api.Response>();
            for (var f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
