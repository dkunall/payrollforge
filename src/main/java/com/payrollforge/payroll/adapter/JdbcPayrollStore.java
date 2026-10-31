package com.payrollforge.payroll.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrollforge.payroll.application.BankFile;
import com.payrollforge.payroll.application.PayrollRun;
import com.payrollforge.payroll.application.PayrollStore;
import com.payrollforge.payroll.application.PayslipRow;
import com.payrollforge.payroll.domain.run.EmployeeSnapshot;
import com.payrollforge.payroll.domain.run.PayslipComputation.ArrearsLine;
import com.payrollforge.payroll.domain.run.RunStatus;
import com.payrollforge.payroll.domain.structure.ComponentKind;
import com.payrollforge.payroll.domain.structure.Computation;
import com.payrollforge.payroll.domain.structure.PayslipLine;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Repository
class JdbcPayrollStore implements PayrollStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LINES = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final String SETTLED = "('FINALIZED', 'PAID')";

    private final JdbcClient db;
    private final ObjectMapper json;

    JdbcPayrollStore(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    // ---- runs

    @Override
    public void advisoryLock(String name) {
        db.sql("select pg_advisory_xact_lock(hashtext(?))").param(name).query((rs, i) -> 1).list();
    }

    @Override
    public Optional<PayrollRun> runByIdemKey(String key) {
        return db.sql("select * from payroll_run where idem_key = ?").param(key).query(this::mapRun).optional();
    }

    @Override
    public boolean openRunExists(String entity) {
        return db.sql("select count(*) from payroll_run where entity = ? and status in ('DRAFT', 'COMPUTING', 'REVIEW', 'APPROVED')")
                .param(entity).query(Long.class).single() > 0;
    }

    @Override
    public Optional<YearMonth> latestSettledPeriod(String entity) {
        return db.sql("select max(period) from payroll_run where entity = ? and status in " + SETTLED).param(entity)
                .query(String.class).optional().map(YearMonth::parse);
    }

    @Override
    public void insertRun(UUID id, String entity, YearMonth period, String idemKey, String createdBy, Instant at) {
        db.sql("""
                        insert into payroll_run (id, entity, period, status, idem_key, created_by, created_at, version)
                        values (?, ?, ?, 'DRAFT', ?, ?, ?, 0)""")
                .params(id, entity, period.toString(), idemKey, createdBy, Timestamp.from(at)).update();
    }

    @Override
    public Optional<PayrollRun> run(UUID id) {
        return db.sql("select * from payroll_run where id = ?").param(id).query(this::mapRun).optional();
    }

    @Override
    public Optional<PayrollRun> lockRun(UUID id) {
        return db.sql("select * from payroll_run where id = ? for update").param(id).query(this::mapRun).optional();
    }

    @Override
    public List<PayrollRun> runs(String entity, int limit) {
        return db.sql("select * from payroll_run where entity = ? order by period desc, created_at desc limit ?")
                .params(entity, limit).query(this::mapRun).list();
    }

    @Override
    public void startRun(UUID id, String hash, Map<String, Object> taxRules, int employees, List<String> warnings,
                         Instant at) {
        db.sql("""
                        update payroll_run set status = 'COMPUTING', input_snapshot_hash = ?, tax_rules = ?::jsonb,
                          employee_count = ?, warnings = ?::jsonb, started_at = ?, computed_at = null,
                          submitted_by = null, approved_by = null, version = version + 1 where id = ?""")
                .params(hash, write(taxRules), employees, write(warnings), Timestamp.from(at), id).update();
    }

    @Override
    public void setStatus(UUID id, RunStatus status) {
        db.sql("update payroll_run set status = ?, version = version + 1 where id = ?").params(status.name(), id).update();
    }

    @Override
    public void markComputed(UUID id, Instant at) {
        db.sql("update payroll_run set status = 'REVIEW', computed_at = ?, version = version + 1 where id = ?")
                .params(Timestamp.from(at), id).update();
    }

    @Override
    public void setSubmitted(UUID id, String by) {
        db.sql("update payroll_run set submitted_by = ?, version = version + 1 where id = ?").params(by, id).update();
    }

    @Override
    public void returnToDraft(UUID id, String warning) {
        db.sql("""
                        update payroll_run set status = 'DRAFT', submitted_by = null, approved_by = null,
                          warnings = warnings || jsonb_build_array(?::text), version = version + 1 where id = ?""")
                .params(warning, id).update();
        db.sql("delete from payroll_lock where run_id = ?").param(id).update();
    }

    @Override
    public void setApproved(UUID id, String by) {
        db.sql("update payroll_run set status = 'APPROVED', approved_by = ?, version = version + 1 where id = ?")
                .params(by, id).update();
    }

    @Override
    public void setFinalized(UUID id, String by, Instant at) {
        db.sql("update payroll_run set status = 'FINALIZED', finalized_by = ?, finalized_at = ?, version = version + 1 where id = ?")
                .params(by, Timestamp.from(at), id).update();
    }

    @Override
    public void setPaid(UUID id, Instant at) {
        db.sql("update payroll_run set status = 'PAID', paid_at = ?, version = version + 1 where id = ?")
                .params(Timestamp.from(at), id).update();
    }

    @Override
    public Map<YearMonth, UUID> settledRuns(String entity, YearMonth from, YearMonth to) {
        var out = new TreeMap<YearMonth, UUID>();
        db.sql("select period, id from payroll_run where entity = ? and status in " + SETTLED + " and period between ? and ?")
                .params(entity, from.toString(), to.toString())
                .query(rs -> {
                    out.put(YearMonth.parse(rs.getString(1)), rs.getObject(2, UUID.class));
                });
        return out;
    }

    // ---- inputs and chunks

    @Override
    public void clearComputed(UUID runId) {
        db.sql("delete from arrear_line where run_id = ?").param(runId).update();
        db.sql("delete from payslip where run_id = ?").param(runId).update();
        db.sql("delete from payroll_chunk where run_id = ?").param(runId).update();
        db.sql("delete from payroll_input where run_id = ?").param(runId).update();
    }

    @Override
    public void insertInputs(UUID runId, Map<UUID, EmployeeSnapshot> snapshots) {
        for (var e : snapshots.entrySet()) {
            db.sql("insert into payroll_input (run_id, employee_id, snapshot, hash) values (?, ?, ?::jsonb, ?)")
                    .params(runId, e.getKey(), write(e.getValue().toMap()), e.getValue().hash()).update();
        }
    }

    @Override
    public Map<UUID, EmployeeSnapshot> inputs(UUID runId, Collection<UUID> employees) {
        var out = new LinkedHashMap<UUID, EmployeeSnapshot>();
        db.sql("select employee_id, snapshot from payroll_input where run_id = ? and employee_id = any(?) order by snapshot->>'code'")
                .params(runId, employees.toArray(UUID[]::new))
                .query(rs -> {
                    out.put(rs.getObject(1, UUID.class), EmployeeSnapshot.fromMap(read(rs.getString(2), MAP)));
                });
        return out;
    }

    @Override
    public Map<UUID, String> inputHashes(UUID runId) {
        var out = new LinkedHashMap<UUID, String>();
        db.sql("select employee_id, hash from payroll_input where run_id = ?").param(runId)
                .query(rs -> {
                    out.put(rs.getObject(1, UUID.class), rs.getString(2));
                });
        return out;
    }

    @Override
    public void insertChunks(UUID runId, List<List<UUID>> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            db.sql("insert into payroll_chunk (run_id, chunk_no, employee_ids, status, attempts) values (?, ?, ?, 'PENDING', 0)")
                    .params(runId, i, chunks.get(i).toArray(UUID[]::new)).update();
        }
    }

    @Override
    public List<Chunk> chunks(UUID runId) {
        return db.sql("select * from payroll_chunk where run_id = ? order by chunk_no").param(runId).query(this::chunk).list();
    }

    @Override
    public void resetUnfinishedChunks(UUID runId) {
        db.sql("update payroll_chunk set status = 'PENDING' where run_id = ? and status in ('FAILED', 'RUNNING')")
                .param(runId).update();
    }

    @Override
    public Optional<Chunk> claimChunk(UUID runId, int chunkNo) {
        return db.sql("""
                        update payroll_chunk set status = 'RUNNING', attempts = attempts + 1, error = null
                        where run_id = ? and chunk_no = ? and status = 'PENDING' returning *""")
                .params(runId, chunkNo).query(this::chunk).optional();
    }

    @Override
    public void chunkDone(UUID runId, int chunkNo) {
        db.sql("update payroll_chunk set status = 'DONE' where run_id = ? and chunk_no = ?").params(runId, chunkNo).update();
    }

    @Override
    public void chunkFailed(UUID runId, int chunkNo, String error) {
        db.sql("update payroll_chunk set status = 'FAILED', attempts = attempts + 1, error = ? where run_id = ? and chunk_no = ? and status <> 'DONE'")
                .params(error, runId, chunkNo).update();
    }

    // ---- payslips

    @Override
    public void savePayslip(UUID runId, UUID employee, Computation c, Map<String, BigDecimal> inputs,
                            BigDecimal taxable, BigDecimal tds, List<ArrearsLine> arrears) {
        var lines = new ArrayList<Map<String, Object>>();
        for (PayslipLine l : c.lines()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("code", l.code());
            m.put("label", l.label());
            m.put("kind", l.kind().name());
            m.put("amount", l.amount().toPlainString());
            m.put("taxable", l.taxable());
            lines.add(m);
        }
        var in = new TreeMap<String, String>();
        inputs.forEach((k, v) -> in.put(k, v.toPlainString()));
        db.sql("""
                        insert into payslip (run_id, employee_id, lines, inputs, gross, deductions, net, taxable, tds)
                        values (?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
                        on conflict (run_id, employee_id) do update set lines = excluded.lines, inputs = excluded.inputs,
                          gross = excluded.gross, deductions = excluded.deductions, net = excluded.net,
                          taxable = excluded.taxable, tds = excluded.tds""")
                .params(runId, employee, write(lines), write(in), c.gross(), c.deductions(), c.net(), taxable, tds)
                .update();
        db.sql("delete from arrear_line where run_id = ? and employee_id = ?").params(runId, employee).update();
        // the same component can appear once per earlier period; sum in case a structure repeats a code
        var merged = new LinkedHashMap<String, ArrearsLine>();
        for (ArrearsLine a : arrears) {
            merged.merge(a.period() + "|" + a.component(), a, (x, y) -> new ArrearsLine(x.period(), x.component(),
                    x.kind(), x.amount().add(y.amount())));
        }
        for (ArrearsLine a : merged.values()) {
            db.sql("insert into arrear_line (run_id, employee_id, for_period, component, kind, amount) values (?, ?, ?, ?, ?, ?)")
                    .params(runId, employee, a.period(), a.component(), a.kind().name(), a.amount()).update();
        }
    }

    private static final String SLIP = """
            select s.*, r.period, i.snapshot->>'code' as code, i.snapshot->>'name' as name from payslip s
            join payroll_run r on r.id = s.run_id
            join payroll_input i on i.run_id = s.run_id and i.employee_id = s.employee_id""";

    @Override
    public List<PayslipRow> payslips(UUID runId) {
        return db.sql(SLIP + " where s.run_id = ? order by code").param(runId).query(this::slip).list();
    }

    @Override
    public Optional<PayslipRow> payslip(UUID runId, UUID employee) {
        return db.sql(SLIP + " where s.run_id = ? and s.employee_id = ?").params(runId, employee).query(this::slip).optional();
    }

    @Override
    public List<PayslipRow> settledPayslips(UUID employee) {
        return db.sql(SLIP + " where s.employee_id = ? and r.status in " + SETTLED + " order by r.period desc")
                .param(employee).query(this::slip).list();
    }

    @Override
    public Map<UUID, Map<String, BigDecimal>> payslipAmounts(UUID runId, Collection<UUID> employees) {
        var out = new HashMap<UUID, Map<String, BigDecimal>>();
        db.sql("select employee_id, lines from payslip where run_id = ? and employee_id = any(?)")
                .params(runId, employees.toArray(UUID[]::new))
                .query(rs -> {
                    var m = new TreeMap<String, BigDecimal>();
                    for (var l : read(rs.getString(2), LINES)) {
                        if (!"INFO".equals(l.get("kind"))) {
                            m.put((String) l.get("code"), new BigDecimal(l.get("amount").toString()));
                        }
                    }
                    out.put(rs.getObject(1, UUID.class), m);
                });
        return out;
    }

    @Override
    public List<ArrearsLine> arrears(UUID runId, UUID employee) {
        return db.sql("select * from arrear_line where run_id = ? and employee_id = ? order by for_period, component")
                .params(runId, employee)
                .query((rs, i) -> new ArrearsLine(rs.getString("for_period"), rs.getString("component"),
                        ComponentKind.valueOf(rs.getString("kind")), rs.getBigDecimal("amount")))
                .list();
    }

    @Override
    public Map<UUID, Map<String, Map<String, BigDecimal>>> settledArrears(Collection<UUID> employees) {
        var out = new HashMap<UUID, Map<String, Map<String, BigDecimal>>>();
        db.sql("""
                        select a.employee_id, a.for_period, a.component, sum(a.amount) from arrear_line a
                        join payroll_run r on r.id = a.run_id
                        where r.status in""" + " " + SETTLED + " and a.employee_id = any(?) group by 1, 2, 3")
                .param(employees.toArray(UUID[]::new))
                .query(rs -> {
                    out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashMap<>())
                            .computeIfAbsent(rs.getString(2), k -> new TreeMap<>())
                            .put(rs.getString(3), rs.getBigDecimal(4));
                });
        return out;
    }

    @Override
    public Map<UUID, Ytd> ytd(Collection<UUID> employees, String entity, YearMonth fromInclusive, YearMonth toExclusive) {
        var out = new HashMap<UUID, Ytd>();
        db.sql("""
                        select s.employee_id, sum(s.taxable), sum(s.tds) from payslip s join payroll_run r on r.id = s.run_id
                        where r.entity = ? and r.status in""" + " " + SETTLED + """
                         and r.period >= ? and r.period < ? and s.employee_id = any(?) group by 1""")
                .params(entity, fromInclusive.toString(), toExclusive.toString(), employees.toArray(UUID[]::new))
                .query(rs -> {
                    out.put(rs.getObject(1, UUID.class), new Ytd(rs.getBigDecimal(2), rs.getBigDecimal(3)));
                });
        return out;
    }

    // ---- attendance projection

    @Override
    public boolean upsertDay(UUID employee, LocalDate day, String status, BigDecimal payable, int version) {
        return db.sql("""
                        insert into payroll_day (employee_id, day, status, payable, version) values (?, ?, ?, ?, ?)
                        on conflict (employee_id, day) do update set status = excluded.status, payable = excluded.payable,
                          version = excluded.version where payroll_day.version < excluded.version""")
                .params(employee, day, status, payable, version).update() == 1;
    }

    @Override
    public Map<UUID, Map<LocalDate, BigDecimal>> payableDays(Collection<UUID> employees, LocalDate from, LocalDate to) {
        var out = new HashMap<UUID, Map<LocalDate, BigDecimal>>();
        db.sql("select employee_id, day, payable from payroll_day where employee_id = any(?) and day between ? and ?")
                .params(employees.toArray(UUID[]::new), from, to)
                .query(rs -> {
                    out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashMap<>())
                            .put(rs.getObject(2, LocalDate.class), rs.getBigDecimal(3));
                });
        return out;
    }

    // ---- pdf

    @Override
    public void queuePdfJobs(UUID runId, Instant at) {
        db.sql("""
                        insert into pdf_job (run_id, employee_id, status, attempts, updated_at)
                        select run_id, employee_id, 'PENDING', 0, ? from payslip where run_id = ? on conflict do nothing""")
                .params(Timestamp.from(at), runId).update();
    }

    @Override
    public List<PdfJob> claimPdfJobs(int limit) {
        return db.sql("select run_id, employee_id, attempts from pdf_job where status = 'PENDING' order by updated_at limit ? for update skip locked")
                .param(limit)
                .query((rs, i) -> new PdfJob(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3)))
                .list();
    }

    @Override
    public void pdfDone(PdfJob job, String key, Instant at) {
        db.sql("update payslip set pdf_key = ? where run_id = ? and employee_id = ?").params(key, job.runId(), job.employeeId()).update();
        db.sql("update pdf_job set status = 'DONE', attempts = attempts + 1, updated_at = ? where run_id = ? and employee_id = ?")
                .params(Timestamp.from(at), job.runId(), job.employeeId()).update();
    }

    @Override
    public void pdfFailed(PdfJob job, String error, boolean giveUp, Instant at) {
        db.sql("update pdf_job set status = ?, attempts = attempts + 1, error = ?, updated_at = ? where run_id = ? and employee_id = ?")
                .params(giveUp ? "FAILED" : "PENDING", error, Timestamp.from(at), job.runId(), job.employeeId()).update();
    }

    @Override
    public Map<String, Long> pdfStatus(UUID runId) {
        var out = new TreeMap<String, Long>();
        db.sql("select status, count(*) from pdf_job where run_id = ? group by status").param(runId)
                .query(rs -> {
                    out.put(rs.getString(1), rs.getLong(2));
                });
        return out;
    }

    // ---- bank files

    @Override
    public Optional<BankFile> activeBankFile(UUID runId) {
        return db.sql("select * from bank_file where run_id = ? and status = 'ACTIVE'").param(runId)
                .query(JdbcPayrollStore::bankFile).optional();
    }

    @Override
    public int bankFileCount(UUID runId) {
        return db.sql("select count(*) from bank_file where run_id = ?").param(runId).query(Integer.class).single();
    }

    @Override
    public boolean insertBankFile(BankFile f) {
        return db.sql("""
                        insert into bank_file (id, run_id, batch_ref, status, object_key, sha256, total, rows, created_by, approved_by, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing""")
                .params(f.id(), f.runId(), f.batchRef(), f.status(), f.objectKey(), f.sha256(), f.total(), f.rows(),
                        f.createdBy(), f.approvedBy(), Timestamp.from(f.createdAt()))
                .update() == 1;
    }

    @Override
    public void voidBankFile(UUID id) {
        db.sql("update bank_file set status = 'VOID' where id = ?").param(id).update();
    }

    @Override
    public boolean insertReissue(Reissue r) {
        return db.sql("""
                        insert into bank_file_reissue (id, run_id, requested_by, reason, status, created_at)
                        values (?, ?, ?, ?, 'PENDING', ?) on conflict do nothing""")
                .params(r.id(), r.runId(), r.requestedBy(), r.reason(), Timestamp.from(r.createdAt())).update() == 1;
    }

    @Override
    public Optional<Reissue> lockReissue(UUID id) {
        return db.sql("select * from bank_file_reissue where id = ? for update").param(id)
                .query((rs, i) -> new Reissue(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                        rs.getString("requested_by"), rs.getString("reason"), rs.getString("status"),
                        rs.getString("approved_by"), rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    @Override
    public void approveReissue(UUID id, String by) {
        db.sql("update bank_file_reissue set status = 'APPROVED', approved_by = ? where id = ?").params(by, id).update();
    }

    // ---- mapping

    private PayrollRun mapRun(ResultSet rs, int i) throws SQLException {
        String rules = rs.getString("tax_rules");
        return new PayrollRun(rs.getObject("id", UUID.class), rs.getString("entity"), YearMonth.parse(rs.getString("period")),
                RunStatus.valueOf(rs.getString("status")), rs.getString("input_snapshot_hash"),
                rules == null ? null : read(rules, MAP), (Integer) rs.getObject("employee_count"),
                read(rs.getString("warnings"), STRINGS), rs.getString("created_by"), rs.getString("submitted_by"),
                rs.getString("approved_by"), rs.getString("finalized_by"), instant(rs, "created_at"),
                instant(rs, "started_at"), instant(rs, "computed_at"), instant(rs, "finalized_at"),
                instant(rs, "paid_at"));
    }

    private Chunk chunk(ResultSet rs, int i) throws SQLException {
        Array a = rs.getArray("employee_ids");
        var ids = Arrays.stream((Object[]) a.getArray()).map(o -> (UUID) o).toList();
        return new Chunk(rs.getObject("run_id", UUID.class), rs.getInt("chunk_no"), ids, rs.getString("status"),
                rs.getInt("attempts"), rs.getString("error"));
    }

    private PayslipRow slip(ResultSet rs, int i) throws SQLException {
        return new PayslipRow(rs.getObject("run_id", UUID.class), YearMonth.parse(rs.getString("period")),
                rs.getObject("employee_id", UUID.class), rs.getString("code"), rs.getString("name"),
                read(rs.getString("lines"), LINES), read(rs.getString("inputs"), STRING_MAP), rs.getBigDecimal("gross"),
                rs.getBigDecimal("deductions"), rs.getBigDecimal("net"), rs.getBigDecimal("taxable"),
                rs.getBigDecimal("tds"), rs.getString("pdf_key"));
    }

    private static BankFile bankFile(ResultSet rs, int i) throws SQLException {
        return new BankFile(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class), rs.getString("batch_ref"),
                rs.getString("status"), rs.getString("object_key"), rs.getString("sha256"), rs.getBigDecimal("total"),
                rs.getInt("rows"), rs.getString("created_by"), rs.getString("approved_by"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private String write(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String s, TypeReference<T> type) {
        try {
            return json.readValue(s, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
