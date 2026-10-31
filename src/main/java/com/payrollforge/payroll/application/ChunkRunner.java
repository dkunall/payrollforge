package com.payrollforge.payroll.application;

import com.payrollforge.payroll.domain.run.EmployeeSnapshot;
import com.payrollforge.payroll.domain.run.PayslipComputation;
import com.payrollforge.payroll.domain.run.RunStatus;
import com.payrollforge.payroll.domain.structure.Computation;
import com.payrollforge.payroll.domain.structure.Inputs;
import com.payrollforge.payroll.domain.structure.PayslipLine;
import com.payrollforge.shared.config.PayrollForgeProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Computes a run's chunks on virtual threads, at most {@code parallelism} at once. A chunk is claimed with a
 * conditional update and computed in one transaction, so a chunk's payslips are written exactly once: a retry
 * either sees DONE or replaces the whole chunk.
 */
@Component
public class ChunkRunner {

    private static final Logger log = LoggerFactory.getLogger(ChunkRunner.class);

    private final PayrollStore store;
    private final StructureService structures;
    private final ObjectProvider<ChunkHook> hook;
    private final PayrollForgeProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private final Counter failedChunks;
    private final Timer duration;

    public ChunkRunner(PayrollStore store, StructureService structures, ObjectProvider<ChunkHook> hook,
                       PayrollForgeProperties props, TransactionTemplate tx, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.structures = structures;
        this.hook = hook;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
        this.failedChunks = Counter.builder("payrollforge.payroll.chunks.failed").register(meters);
        this.duration = Timer.builder("payrollforge.payroll.run.compute").description("start to REVIEW, per run")
                .register(meters);
    }

    public boolean isRunning(UUID runId) {
        return running.contains(runId);
    }

    public void launch(UUID runId) {
        if (!running.add(runId)) {
            return;
        }
        Thread.ofVirtual().name("payroll-run-" + runId).start(() -> {
            try {
                computeAll(runId);
            } catch (RuntimeException e) {
                log.error("payroll run {} stopped: {}", runId, e.toString());
            } finally {
                running.remove(runId);
            }
        });
    }

    private void computeAll(UUID runId) {
        var gate = new Semaphore(Math.max(1, props.payroll().parallelism()));
        var futures = new ArrayList<Future<?>>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var c : store.chunks(runId)) {
                if (c.status().equals("PENDING")) {
                    futures.add(pool.submit(() -> {
                        gate.acquireUninterruptibly();
                        try {
                            computeChunk(runId, c.chunkNo());
                        } finally {
                            gate.release();
                        }
                    }));
                }
            }
        }
        tx.executeWithoutResult(s -> {
            var run = store.lockRun(runId).orElseThrow();
            if (run.status() == RunStatus.COMPUTING
                    && store.chunks(runId).stream().allMatch(c -> c.status().equals("DONE"))) {
                store.markComputed(runId, clock.instant());
                if (run.startedAt() != null) {
                    duration.record(Duration.between(run.startedAt(), clock.instant()));
                }
            }
        });
    }

    void computeChunk(UUID runId, int chunkNo) {
        try {
            tx.executeWithoutResult(s -> {
                var chunk = store.claimChunk(runId, chunkNo);
                if (chunk.isEmpty()) {
                    return;
                }
                var h = hook.getIfAvailable();
                if (h != null) {
                    h.beforeChunk(runId, chunkNo);
                }
                var run = store.run(runId).orElseThrow();
                var rules = TaxRulesCodec.fromMap(run.taxRules());
                var compiler = structures.compiler();
                for (EmployeeSnapshot snap : store.inputs(runId, chunk.get().employees()).values()) {
                    var result = PayslipComputation.current(snap, rules, compiler).run();
                    Computation c = result.payslip();
                    store.savePayslip(runId, snap.employeeId(), c, result.inputs(), taxable(c), c.amount("TDS"),
                            result.arrears());
                }
                store.chunkDone(runId, chunkNo);
            });
        } catch (RuntimeException e) {
            failedChunks.increment();
            String msg = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            log.warn("payroll run {} chunk {} failed: {}", runId, chunkNo, msg);
            tx.executeWithoutResult(s -> store.chunkFailed(runId, chunkNo, msg.length() > 500 ? msg.substring(0, 500) : msg));
        }
    }

    static BigDecimal taxable(Computation c) {
        BigDecimal v = c.values().get(Inputs.TAXABLE_GROSS);
        if (v != null) {
            return v.setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return c.lines().stream().filter(PayslipLine::taxable).map(PayslipLine::amount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }
}
