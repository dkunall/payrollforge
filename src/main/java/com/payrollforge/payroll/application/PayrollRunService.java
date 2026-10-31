package com.payrollforge.payroll.application;

import com.payrollforge.outbox.application.Outbox;
import com.payrollforge.payroll.domain.run.EmployeeSnapshot;
import com.payrollforge.payroll.domain.run.RunEvent;
import com.payrollforge.payroll.domain.run.RunStatus;
import com.payrollforge.shared.config.PayrollForgeProperties;
import com.payrollforge.shared.domain.DomainConflictException;
import com.payrollforge.shared.domain.DomainRuleException;
import com.payrollforge.shared.error.ApiException;
import com.payrollforge.shared.lock.PeriodLocks;
import com.payrollforge.shared.security.Caller;
import com.payrollforge.shared.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The run lifecycle. Each step locks the run row, checks the state machine, and does its work in one transaction.
 * Approve and finalize rebuild the input snapshot and compare hashes; a mismatch sends the run back to DRAFT and
 * that transition is committed before the 409 goes out.
 */
@Service
public class PayrollRunService {

    public record Created(PayrollRun run, boolean created) {
    }

    private final PayrollStore store;
    private final SnapshotBuilder snapshots;
    private final ChunkRunner runner;
    private final PeriodLocks locks;
    private final Outbox outbox;
    private final PayrollSources sources;
    private final PayrollForgeProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PayrollRunService(PayrollStore store, SnapshotBuilder snapshots, ChunkRunner runner, PeriodLocks locks,
                             Outbox outbox, PayrollSources sources, PayrollForgeProperties props, TransactionTemplate tx,
                             Clock clock) {
        this.store = store;
        this.snapshots = snapshots;
        this.runner = runner;
        this.locks = locks;
        this.outbox = outbox;
        this.sources = sources;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    private String entity() {
        return props.payroll().entity();
    }

    public Created create(Caller caller, YearMonth period, String idemKey) {
        caller.require(Role.PAYROLL_MAKER);
        if (idemKey == null || idemKey.isBlank() || idemKey.length() > 100) {
            throw ApiException.invalid("idempotency-key-required", "send an Idempotency-Key header of 1 to 100 characters");
        }
        return tx.execute(s -> {
            // one creator at a time: the checks below read rows a concurrent creator could be inserting
            store.advisoryLock("payroll-run-create:" + entity());
            var existing = store.runByIdemKey(idemKey);
            if (existing.isPresent()) {
                if (!existing.get().period().equals(period)) {
                    throw ApiException.unprocessable("idempotency-key-reused",
                            "this Idempotency-Key was used for " + existing.get().period());
                }
                return new Created(existing.get(), false);
            }
            if (store.openRunExists(entity())) {
                throw new DomainConflictException("open-run-exists",
                        "finish or cancel the open payroll run before creating another");
            }
            var latest = store.latestSettledPeriod(entity());
            if (latest.isPresent() && !period.isAfter(latest.get())) {
                throw new DomainRuleException("period-out-of-order",
                        "payroll for " + latest.get() + " is already settled; correct earlier months with arrears");
            }
            UUID id = UUID.randomUUID();
            store.insertRun(id, entity(), period, idemKey, caller.subject(), clock.instant());
            return new Created(store.run(id).orElseThrow(), true);
        });
    }

    public PayrollRun get(Caller caller, UUID id) {
        caller.require(Role.HR, Role.PAYROLL_MAKER, Role.PAYROLL_CHECKER);
        return find(id);
    }

    public List<PayrollRun> list(Caller caller) {
        caller.require(Role.HR, Role.PAYROLL_MAKER, Role.PAYROLL_CHECKER);
        return store.runs(entity(), 50);
    }

    public record Progress(PayrollRun run, Map<String, Long> chunks, List<PayrollStore.Chunk> failed,
                           Map<String, Long> pdfs) {
    }

    public Progress progress(Caller caller, UUID id) {
        var run = get(caller, id);
        var counts = new LinkedHashMap<String, Long>();
        var failed = new ArrayList<PayrollStore.Chunk>();
        for (var c : store.chunks(id)) {
            counts.merge(c.status(), 1L, Long::sum);
            if (c.status().equals("FAILED")) {
                failed.add(c);
            }
        }
        return new Progress(run, counts, failed, store.pdfStatus(id));
    }

    /** DRAFT: snapshot inputs and split into chunks. COMPUTING: resume chunks that failed or never finished. */
    public PayrollRun start(Caller caller, UUID id) {
        caller.require(Role.PAYROLL_MAKER);
        tx.executeWithoutResult(s -> {
            var run = lock(id);
            transition(run, RunEvent.START);
            if (run.status() == RunStatus.DRAFT) {
                var rules = TaxRulesCodec.toMap(props.payroll().taxRules());
                var built = snapshots.build(entity(), run.period(), TaxRulesCodec.fromMap(rules));
                if (built.snapshots().isEmpty()) {
                    throw new DomainRuleException("no-employees", "nobody is payable in " + run.period());
                }
                store.clearComputed(id);
                store.insertInputs(id, built.snapshots());
                var ids = new ArrayList<>(built.snapshots().keySet());
                var chunks = new ArrayList<List<UUID>>();
                for (int i = 0; i < ids.size(); i += props.payroll().chunkSize()) {
                    chunks.add(List.copyOf(ids.subList(i, Math.min(ids.size(), i + props.payroll().chunkSize()))));
                }
                store.insertChunks(id, chunks);
                store.startRun(id, hash(rules, built.snapshots()), rules, ids.size(), built.warnings(), clock.instant());
            } else {
                if (runner.isRunning(id)) {
                    throw new DomainConflictException("already-computing", "this run is computing right now");
                }
                store.resetUnfinishedChunks(id);
            }
        });
        runner.launch(id);
        return find(id);
    }

    public PayrollRun submit(Caller caller, UUID id) {
        caller.require(Role.PAYROLL_MAKER);
        return tx.execute(s -> {
            var run = lock(id);
            if (run.status() != RunStatus.REVIEW) {
                throw new DomainConflictException("invalid-transition", "only a run in REVIEW can be submitted");
            }
            if (run.submittedBy() != null) {
                throw new DomainConflictException("already-submitted", "this run is already submitted");
            }
            store.setSubmitted(id, caller.subject());
            return store.run(id).orElseThrow();
        });
    }

    public PayrollRun approve(Caller caller, UUID id) {
        caller.require(Role.PAYROLL_CHECKER);
        boolean changed = Boolean.TRUE.equals(tx.execute(s -> {
            var run = lock(id);
            transition(run, RunEvent.APPROVE);
            if (run.submittedBy() == null) {
                throw new DomainConflictException("not-submitted", "the maker has not submitted this run");
            }
            fourEyes(caller, run.createdBy(), run.submittedBy());
            if (inputsChanged(run)) {
                store.returnToDraft(id, "inputs changed before approval; start the run again");
                return true;
            }
            locks.lock(run.period(), id);
            store.setApproved(id, caller.subject());
            return false;
        }));
        if (changed) {
            throw new DomainConflictException("inputs-changed",
                    "attendance, leave, salary or claims changed after this run started; it is back in DRAFT");
        }
        return find(id);
    }

    public PayrollRun finalizeRun(Caller caller, UUID id) {
        caller.require(Role.PAYROLL_CHECKER);
        boolean changed = Boolean.TRUE.equals(tx.execute(s -> {
            var run = lock(id);
            transition(run, RunEvent.FINALIZE);
            if (inputsChanged(run)) {
                store.returnToDraft(id, "inputs changed after approval; start the run again");
                return true;
            }
            store.setFinalized(id, caller.subject(), clock.instant());
            store.queuePdfJobs(id, clock.instant());
            var inputs = store.inputs(id, store.inputHashes(id).keySet());
            var claims = new ArrayList<UUID>();
            var encash = new ArrayList<UUID>();
            for (EmployeeSnapshot snap : inputs.values()) {
                snap.reimbursementIds().forEach(x -> claims.add(UUID.fromString(x)));
                snap.encashmentIds().forEach(x -> encash.add(UUID.fromString(x)));
            }
            sources.settle(id, claims, encash);
            BigDecimal net = store.payslips(id).stream().map(PayslipRow::net).reduce(BigDecimal.ZERO, BigDecimal::add);
            var data = new HashMap<String, Object>();
            data.put("runId", id.toString());
            data.put("entity", run.entity());
            data.put("period", run.period().toString());
            data.put("employees", inputs.size());
            data.put("netTotal", net.setScale(2).toPlainString());
            outbox.append(Outbox.PAYROLL, run.period().toString(), PayrollEvents.FINALIZED, data);
            return false;
        }));
        if (changed) {
            throw new DomainConflictException("inputs-changed",
                    "inputs changed after approval; the run is back in DRAFT and must be started again");
        }
        return find(id);
    }

    public PayrollRun cancel(Caller caller, UUID id) {
        caller.require(Role.PAYROLL_MAKER);
        tx.executeWithoutResult(s -> {
            var run = lock(id);
            transition(run, RunEvent.CANCEL);
            if (run.status() == RunStatus.COMPUTING && runner.isRunning(id)) {
                throw new DomainConflictException("computing", "wait for the chunks in flight to finish");
            }
            store.setStatus(id, RunStatus.CANCELLED);
        });
        return find(id);
    }

    public PayrollRun markPaid(Caller caller, UUID id, String batchRef) {
        caller.require(Role.PAYROLL_CHECKER);
        return tx.execute(s -> {
            var run = lock(id);
            transition(run, RunEvent.PAY);
            var file = store.activeBankFile(id).orElseThrow(() -> new DomainConflictException("no-bank-file",
                    "generate the bank file before marking the run paid"));
            if (!file.batchRef().equals(batchRef)) {
                throw new DomainConflictException("bank-file-mismatch",
                        "the active bank file is " + file.batchRef());
            }
            store.setPaid(id, clock.instant());
            return store.run(id).orElseThrow();
        });
    }

    private boolean inputsChanged(PayrollRun run) {
        var built = snapshots.build(run.entity(), run.period(), TaxRulesCodec.fromMap(run.taxRules()));
        return !hash(run.taxRules(), built.snapshots()).equals(run.inputHash());
    }

    static String hash(Map<String, Object> rules, Map<UUID, EmployeeSnapshot> snaps) {
        var hashes = new HashMap<UUID, String>();
        snaps.forEach((k, v) -> hashes.put(k, v.hash()));
        return EmployeeSnapshot.runHash(rules, hashes);
    }

    private static void fourEyes(Caller caller, String... makers) {
        for (String m : makers) {
            if (caller.subject().equals(m)) {
                throw ApiException.forbidden("four-eyes", "the checker must be someone other than the run's maker");
            }
        }
    }

    private static void transition(PayrollRun run, RunEvent e) {
        if (!run.status().accepts(e)) {
            throw new DomainConflictException("invalid-transition",
                    "a " + run.status() + " run cannot " + e.name().toLowerCase().replace('_', ' '));
        }
    }

    private PayrollRun lock(UUID id) {
        return store.lockRun(id).orElseThrow(() -> ApiException.notFound("payroll run " + id));
    }

    private PayrollRun find(UUID id) {
        return store.run(id).orElseThrow(() -> ApiException.notFound("payroll run " + id));
    }
}
