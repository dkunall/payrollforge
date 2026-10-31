package com.payrollforge.payroll.application;

import com.payrollforge.payroll.domain.run.PayslipComputation;
import com.payrollforge.payroll.domain.run.PayslipComputation.ArrearsLine;
import com.payrollforge.payroll.domain.run.RunDiff;
import com.payrollforge.payroll.domain.run.RunStatus;
import com.payrollforge.payroll.domain.structure.PayslipLine;
import com.payrollforge.shared.config.PayrollForgeProperties;
import com.payrollforge.shared.domain.DomainConflictException;
import com.payrollforge.shared.error.ApiException;
import com.payrollforge.shared.security.Caller;
import com.payrollforge.shared.security.Role;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Reading computed payslips: per run, per employee, as a diff, as a register, and re-verified from snapshots. */
@Service
public class PayslipService {

    private static final Set<RunStatus> COMPUTED = Set.of(RunStatus.REVIEW, RunStatus.APPROVED, RunStatus.FINALIZED,
            RunStatus.PAID);

    public record Summary(UUID employeeId, String code, String name, BigDecimal gross, BigDecimal deductions,
                          BigDecimal net) {
    }

    public record Payslip(UUID runId, String period, RunStatus runStatus, UUID employeeId, String code, String name,
                          List<Map<String, Object>> lines, Map<String, String> inputs, List<ArrearsLine> arrears,
                          BigDecimal gross, BigDecimal deductions, BigDecimal net, BigDecimal taxable, BigDecimal tds,
                          boolean pdfReady) {
    }

    public record Verification(UUID runId, int employees, int matched, List<String> mismatches, boolean snapshotHashesOk,
                               boolean runHashOk) {
    }

    private final PayrollStore store;
    private final StructureService structures;
    private final ObjectStore objects;
    private final PayrollForgeProperties props;
    private final Counter outliers;

    public PayslipService(PayrollStore store, StructureService structures, ObjectStore objects,
                          PayrollForgeProperties props, MeterRegistry meters) {
        this.store = store;
        this.structures = structures;
        this.objects = objects;
        this.props = props;
        this.outliers = Counter.builder("payrollforge.payroll.diff.outliers").register(meters);
    }

    public List<Summary> list(Caller caller, UUID runId) {
        caller.require(Role.HR, Role.PAYROLL_MAKER, Role.PAYROLL_CHECKER);
        computedRun(runId);
        return store.payslips(runId).stream()
                .map(p -> new Summary(p.employeeId(), p.code(), p.name(), p.gross(), p.deductions(), p.net())).toList();
    }

    public Payslip get(Caller caller, UUID runId, UUID employee) {
        var run = computedRun(runId);
        boolean staff = caller.hasAny(Role.HR, Role.PAYROLL_MAKER, Role.PAYROLL_CHECKER);
        if (!staff) {
            caller.requireSelfOr(employee);
            if (!RunStatus.SETTLED.contains(run.status())) {
                throw ApiException.notFound("payslip");
            }
        }
        var p = store.payslip(runId, employee).orElseThrow(() -> ApiException.notFound("payslip"));
        return view(run, p);
    }

    public List<Payslip> mine(Caller caller) {
        UUID me = caller.requireEmployee();
        var out = new ArrayList<Payslip>();
        for (var p : store.settledPayslips(me)) {
            out.add(view(store.run(p.runId()).orElseThrow(), p));
        }
        return out;
    }

    public byte[] pdf(Caller caller, UUID runId, UUID employee) {
        var p = get(caller, runId, employee);
        if (!p.pdfReady()) {
            throw new DomainConflictException("pdf-pending", "the PDF is generated after the run is finalized");
        }
        return objects.get(pdfKey(runId, employee)).orElseThrow(() -> ApiException.notFound("payslip pdf"));
    }

    static String pdfKey(UUID runId, UUID employee) {
        return "payslips/" + runId + "/" + employee + ".pdf";
    }

    public RunDiff.Summary diff(Caller caller, UUID runId, UUID against) {
        caller.require(Role.HR, Role.PAYROLL_MAKER, Role.PAYROLL_CHECKER);
        computedRun(runId);
        computedRun(against);
        var s = RunDiff.between(sides(against), sides(runId), props.payroll().outlierRatio());
        outliers.increment(s.outliers());
        return s;
    }

    private Map<UUID, RunDiff.Side> sides(UUID runId) {
        var out = new HashMap<UUID, RunDiff.Side>();
        for (var p : store.payslips(runId)) {
            var lines = new TreeMap<String, BigDecimal>();
            for (var l : p.lines()) {
                lines.put((String) l.get("code"), new BigDecimal(l.get("amount").toString()));
            }
            out.put(p.employeeId(), new RunDiff.Side(p.code(), p.name(), p.net(), lines, p.inputs()));
        }
        return out;
    }

    public String register(Caller caller, UUID runId) {
        caller.require(Role.HR, Role.PAYROLL_MAKER, Role.PAYROLL_CHECKER);
        computedRun(runId);
        var slips = store.payslips(runId);
        var codes = new TreeSet<String>();
        var kinds = new HashMap<String, String>();
        for (var p : slips) {
            for (var l : p.lines()) {
                if (!"INFO".equals(l.get("kind"))) {
                    codes.add((String) l.get("code"));
                    kinds.put((String) l.get("code"), (String) l.get("kind"));
                }
            }
        }
        var order = new ArrayList<String>();
        codes.stream().filter(c -> kinds.get(c).equals("EARNING")).forEach(order::add);
        codes.stream().filter(c -> kinds.get(c).equals("DEDUCTION")).forEach(order::add);
        var sb = new StringBuilder("employee_code,name,payable_days,lop_days");
        order.forEach(c -> sb.append(',').append(c));
        sb.append(",gross,deductions,net\n");
        BigDecimal net = BigDecimal.ZERO.setScale(2);
        for (var p : slips) {
            var amounts = new HashMap<String, String>();
            p.lines().forEach(l -> amounts.put((String) l.get("code"), l.get("amount").toString()));
            sb.append(csv(p.code())).append(',').append(csv(p.name())).append(',')
                    .append(p.inputs().getOrDefault("PAYABLE_DAYS", "")).append(',')
                    .append(p.inputs().getOrDefault("LOP_DAYS", ""));
            order.forEach(c -> sb.append(',').append(amounts.getOrDefault(c, "0.00")));
            sb.append(',').append(p.gross().toPlainString()).append(',').append(p.deductions().toPlainString())
                    .append(',').append(p.net().toPlainString()).append('\n');
            net = net.add(p.net());
        }
        sb.append("TOTAL,").append(slips.size()).append(" employees,,");
        order.forEach(c -> sb.append(','));
        sb.append(",,").append(net.toPlainString()).append('\n');
        return sb.toString();
    }

    /** Recomputes every payslip from its stored snapshot and the run's stored tax rules. */
    public Verification verify(Caller caller, UUID runId) {
        caller.require(Role.HR, Role.PAYROLL_MAKER, Role.PAYROLL_CHECKER);
        var run = computedRun(runId);
        var rules = TaxRulesCodec.fromMap(run.taxRules());
        var hashes = store.inputHashes(runId);
        var snaps = store.inputs(runId, hashes.keySet());
        boolean hashesOk = true;
        var recomputed = new HashMap<UUID, String>();
        for (var e : snaps.entrySet()) {
            String h = e.getValue().hash();
            recomputed.put(e.getKey(), h);
            hashesOk &= h.equals(hashes.get(e.getKey()));
        }
        boolean runOk = PayrollRunService.hash(run.taxRules(), snaps).equals(run.inputHash());
        var stored = new HashMap<UUID, PayslipRow>();
        store.payslips(runId).forEach(p -> stored.put(p.employeeId(), p));
        var compiler = structures.compiler();
        var mismatches = new ArrayList<String>();
        int matched = 0;
        for (var snap : snaps.values()) {
            var c = PayslipComputation.current(snap, rules, compiler).run().payslip();
            var p = stored.get(snap.employeeId());
            if (p == null) {
                mismatches.add(snap.code() + ": no stored payslip");
                continue;
            }
            var mine = new LinkedHashMap<String, String>();
            c.lines().forEach(l -> mine.put(l.code(), l.amount().toPlainString()));
            var theirs = new LinkedHashMap<String, String>();
            p.lines().forEach(l -> theirs.put((String) l.get("code"), l.get("amount").toString()));
            if (mine.equals(theirs) && c.net().compareTo(p.net()) == 0) {
                matched++;
            } else {
                mismatches.add(snap.code() + ": stored net " + p.net().toPlainString() + ", recomputed "
                        + c.net().toPlainString());
            }
        }
        return new Verification(runId, snaps.size(), matched, mismatches, hashesOk, runOk);
    }

    private Payslip view(PayrollRun run, PayslipRow p) {
        return new Payslip(p.runId(), run.period().toString(), run.status(), p.employeeId(), p.code(), p.name(),
                p.lines(), p.inputs(), store.arrears(p.runId(), p.employeeId()), p.gross(), p.deductions(), p.net(),
                p.taxable(), p.tds(), p.pdfKey() != null);
    }

    private PayrollRun computedRun(UUID runId) {
        var run = store.run(runId).orElseThrow(() -> ApiException.notFound("payroll run " + runId));
        if (!COMPUTED.contains(run.status())) {
            throw new DomainConflictException("not-computed", "the run has no payslips yet (" + run.status() + ")");
        }
        return run;
    }

    static String csv(String s) {
        if (s == null) {
            return "";
        }
        String t = s;
        if (!t.isEmpty() && "=+-@".indexOf(t.charAt(0)) >= 0) {
            t = "'" + t;
        }
        return t.contains(",") || t.contains("\"") || t.contains("\n") ? "\"" + t.replace("\"", "\"\"") + "\"" : t;
    }

    static List<Map<String, Object>> lines(List<PayslipLine> lines) {
        var out = new ArrayList<Map<String, Object>>();
        for (var l : lines) {
            var m = new LinkedHashMap<String, Object>();
            m.put("code", l.code());
            m.put("label", l.label());
            m.put("kind", l.kind().name());
            m.put("amount", l.amount().toPlainString());
            m.put("taxable", l.taxable());
            out.add(m);
        }
        return out;
    }
}
