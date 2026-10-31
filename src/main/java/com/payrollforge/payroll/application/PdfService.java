package com.payrollforge.payroll.application;

import com.payrollforge.shared.config.PayrollForgeProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/** Renders queued payslip PDFs. Jobs are claimed with SKIP LOCKED, so several workers never render the same one. */
@Service
public class PdfService {

    private static final int MAX_ATTEMPTS = 5;

    private final PayrollStore store;
    private final PayslipRenderer renderer;
    private final ObjectStore objects;
    private final PayrollForgeProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PdfService(PayrollStore store, PayslipRenderer renderer, ObjectStore objects, PayrollForgeProperties props,
                      TransactionTemplate tx, Clock clock) {
        this.store = store;
        this.renderer = renderer;
        this.objects = objects;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    /** Renders up to {@code limit} PDFs; returns how many succeeded. */
    public int renderBatch(int limit) {
        Integer done = tx.execute(s -> {
            int ok = 0;
            for (var job : store.claimPdfJobs(limit)) {
                try {
                    var p = store.payslip(job.runId(), job.employeeId()).orElseThrow();
                    String key = PayslipService.pdfKey(job.runId(), job.employeeId());
                    objects.put(key, renderer.render(p, props.payroll().entity()), "application/pdf");
                    store.pdfDone(job, key, clock.instant());
                    ok++;
                } catch (RuntimeException e) {
                    store.pdfFailed(job, e.getClass().getSimpleName(), job.attempts() + 1 >= MAX_ATTEMPTS, clock.instant());
                }
            }
            return ok;
        });
        return done == null ? 0 : done;
    }
}
