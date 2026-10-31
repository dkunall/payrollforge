package com.payrollforge.payroll.application;

import com.payrollforge.payroll.domain.run.RunStatus;
import com.payrollforge.shared.config.PayrollForgeProperties;
import com.payrollforge.shared.domain.DomainConflictException;
import com.payrollforge.shared.error.ApiException;
import com.payrollforge.shared.security.Caller;
import com.payrollforge.shared.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.UUID;

/**
 * One ACTIVE bank file per run, enforced by a partial unique index: concurrent generate calls converge on the same
 * file. Replacing it needs a reissue requested by a maker and approved by a different checker.
 */
@Service
public class BankFileService {

    private final PayrollStore store;
    private final PayrollSources sources;
    private final ObjectStore objects;
    private final PayrollForgeProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public BankFileService(PayrollStore store, PayrollSources sources, ObjectStore objects, PayrollForgeProperties props,
                           TransactionTemplate tx, Clock clock) {
        this.store = store;
        this.sources = sources;
        this.objects = objects;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    public BankFile generate(Caller caller, UUID runId) {
        caller.require(Role.PAYROLL_MAKER);
        return tx.execute(s -> {
            var run = store.run(runId).orElseThrow(() -> ApiException.notFound("payroll run " + runId));
            if (!RunStatus.SETTLED.contains(run.status())) {
                throw new DomainConflictException("not-finalized", "bank files are made from finalized runs");
            }
            var active = store.activeBankFile(runId);
            if (active.isPresent()) {
                return active.get();
            }
            var file = build(run, caller.subject(), null);
            if (!store.insertBankFile(file)) {
                return store.activeBankFile(runId).orElseThrow();
            }
            return file;
        });
    }

    public BankFile active(Caller caller, UUID runId) {
        caller.require(Role.PAYROLL_MAKER, Role.PAYROLL_CHECKER);
        return store.activeBankFile(runId).orElseThrow(() -> ApiException.notFound("bank file"));
    }

    public byte[] content(Caller caller, UUID runId) {
        var f = active(caller, runId);
        return objects.get(f.objectKey()).orElseThrow(() -> ApiException.notFound("bank file object"));
    }

    public PayrollStore.Reissue requestReissue(Caller caller, UUID runId, String reason) {
        caller.require(Role.PAYROLL_MAKER);
        if (reason == null || reason.isBlank()) {
            throw ApiException.invalid("a reason is required");
        }
        return tx.execute(s -> {
            var run = store.run(runId).orElseThrow(() -> ApiException.notFound("payroll run " + runId));
            if (run.status() != RunStatus.FINALIZED) {
                throw new DomainConflictException("not-reissuable", "only a finalized, unpaid run's file can be reissued");
            }
            store.activeBankFile(runId).orElseThrow(() -> ApiException.notFound("bank file"));
            var r = new PayrollStore.Reissue(UUID.randomUUID(), runId, caller.subject(), reason.strip(), "PENDING", null,
                    clock.instant());
            if (!store.insertReissue(r)) {
                throw new DomainConflictException("reissue-pending", "a reissue request is already waiting");
            }
            return r;
        });
    }

    public BankFile approveReissue(Caller caller, UUID runId, UUID reissueId) {
        caller.require(Role.PAYROLL_CHECKER);
        return tx.execute(s -> {
            var r = store.lockReissue(reissueId).filter(x -> x.runId().equals(runId))
                    .orElseThrow(() -> ApiException.notFound("reissue " + reissueId));
            if (!r.status().equals("PENDING")) {
                throw new DomainConflictException("already-decided", "this reissue was already approved");
            }
            if (r.requestedBy().equals(caller.subject())) {
                throw ApiException.forbidden("four-eyes", "a different checker must approve the reissue");
            }
            var run = store.lockRun(runId).orElseThrow();
            if (run.status() != RunStatus.FINALIZED) {
                throw new DomainConflictException("not-reissuable", "the run is " + run.status());
            }
            store.activeBankFile(runId).ifPresent(f -> store.voidBankFile(f.id()));
            var file = build(run, r.requestedBy(), caller.subject());
            store.insertBankFile(file);
            store.approveReissue(reissueId, caller.subject());
            return file;
        });
    }

    private BankFile build(PayrollRun run, String by, String approvedBy) {
        var slips = store.payslips(run.id());
        var net = new HashMap<UUID, BigDecimal>();
        slips.forEach(p -> net.put(p.employeeId(), p.net()));
        int seq = store.bankFileCount(run.id()) + 1;
        String batch = "%s-%s-%02d".formatted(run.entity(), run.period().toString().replace("-", ""), seq);
        var sb = new StringBuilder("batch_ref,employee_code,beneficiary,account,ifsc,amount\n");
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        int rows = 0;
        for (var p : sources.payees(net.keySet())) {
            BigDecimal amount = net.get(p.employeeId());
            if (amount.signum() <= 0) {
                continue;
            }
            sb.append(batch).append(',').append(PayslipService.csv(p.code())).append(',')
                    .append(PayslipService.csv(p.name())).append(',').append(p.account()).append(',').append(p.ifsc())
                    .append(',').append(amount.toPlainString()).append('\n');
            total = total.add(amount);
            rows++;
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        String key = "bank-files/" + run.id() + "/" + batch + ".csv";
        objects.put(key, bytes, "text/csv");
        return new BankFile(UUID.randomUUID(), run.id(), batch, "ACTIVE", key, sha256(bytes), total, rows, by,
                approvedBy, clock.instant());
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    String entity() {
        return props.payroll().entity();
    }
}
