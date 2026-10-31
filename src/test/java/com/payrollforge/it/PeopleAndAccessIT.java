package com.payrollforge.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.payrollforge.support.ItBase;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PeopleAndAccessIT extends ItBase {

    @Test
    void everyCallNeedsATokenAndTheRightRole() {
        anon().get("/v1/employees").expect(401);
        Person a = employee(LocalDate.now().minusMonths(2), "700000.00");
        Person b = employee(LocalDate.now().minusMonths(2), "700000.00");

        a.api().get("/v1/employees/" + a.id()).expect(200);
        a.api().get("/v1/employees/" + b.id()).expect(403);
        // the directory is open to every employee, but carries no pay or identity numbers
        var directory = a.api().get("/v1/employees").expect(200).body();
        assertThat(directory.toString()).doesNotContain("ctc", "pan", "bankAccount");
        a.api().post("/v1/employees/" + b.id() + "/leave-grants",
                Map.of("type", "CASUAL", "days", 5, "ref", "self-grant")).expect(403);
        a.api().post("/v1/payroll-runs", Map.of("period", "2027-01"), Map.of("Idempotency-Key", "nope")).expect(403);
    }

    @Test
    void bankAccountAndPanAreEncryptedAtRestAndMaskedOnTheWay() {
        Person p = employee(LocalDate.now().minusMonths(2), "700000.00");
        var row = db.sql("select pan, bank_account from employee where id = ?::uuid").param(p.id()).query().singleRow();
        assertThat(row.get("pan").toString()).doesNotMatch("[A-Z]{5}[0-9]{4}[A-Z]");
        assertThat(row.get("bank_account").toString()).doesNotMatch("[0-9]{9,18}");

        var view = hr().get("/v1/employees/" + p.id()).expect(200);
        assertThat(view.text("pan")).doesNotMatch("[A-Z]{5}[0-9]{4}[A-Z]");
        assertThat(view.text("bankAccount")).doesNotMatch("[0-9]{9,18}");
    }

    @Test
    void badInputIsRefusedWithAReason() {
        Map<String, Object> body = new HashMap<>(Map.of("code", "X" + tag(), "name", "Bad Pan",
                "email", "bad@it.example.test", "joinedOn", "2026-01-01", "bankAccount", "123456789",
                "bankIfsc", "SYNT0000001", "pan", "not-a-pan", "grade", "L2", "dept", "ENG"));
        body.putAll(Map.of("location", defaultLocation(), "ctcAnnual", 500000, "structureCode", "STD"));
        var bad = hr().post("/v1/employees", body);
        assertThat(bad.status()).isIn(400, 422);
        assertThat(bad.text("code")).isNotBlank();

        Person p = employee(LocalDate.now().minusMonths(2), "700000.00");
        String recent = LocalDate.now().minusDays(3).toString();
        assertThat(p.api().post("/v1/reimbursements",
                Map.of("category", "MEALS", "amount", "10.999", "spentOn", recent)).status()).isIn(400, 422);
        assertThat(p.api().post("/v1/reimbursements",
                Map.of("category", "MEALS", "amount", "200000.01", "spentOn", recent)).status()).isIn(400, 422);
        assertThat(p.api().post("/v1/reimbursements", Map.of("category", "MEALS", "amount", "10.00",
                "spentOn", LocalDate.now().minusDays(120).toString())).status()).isIn(400, 422);
        assertThat(p.api().post("/v1/reimbursements",
                Map.of("category", "YACHT", "amount", "10.00", "spentOn", recent)).status()).isIn(400, 422);
        p.api().post("/v1/reimbursements", Map.of("category", "MEALS", "amount", "10.50", "spentOn", recent)).expect(201);
    }
}
