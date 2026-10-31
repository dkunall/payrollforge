package com.payrollforge.payroll.application;

import com.payrollforge.payroll.domain.run.EmployeeSnapshot;
import com.payrollforge.payroll.domain.run.PayslipComputation.ArrearsLine;
import com.payrollforge.payroll.domain.run.RunStatus;
import com.payrollforge.payroll.domain.structure.Computation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface PayrollStore {

    record Chunk(UUID runId, int chunkNo, List<UUID> employees, String status, int attempts, String error) {
    }

    record Ytd(BigDecimal taxable, BigDecimal tds) {
    }

    record Reissue(UUID id, UUID runId, String requestedBy, String reason, String status, String approvedBy,
                   Instant createdAt) {
    }

    // runs
    void advisoryLock(String name);

    Optional<PayrollRun> runByIdemKey(String key);

    boolean openRunExists(String entity);

    Optional<YearMonth> latestSettledPeriod(String entity);

    void insertRun(UUID id, String entity, YearMonth period, String idemKey, String createdBy, Instant at);

    Optional<PayrollRun> run(UUID id);

    Optional<PayrollRun> lockRun(UUID id);

    List<PayrollRun> runs(String entity, int limit);

    void startRun(UUID id, String hash, Map<String, Object> taxRules, int employees, List<String> warnings, Instant at);

    void setStatus(UUID id, RunStatus status);

    void markComputed(UUID id, Instant at);

    void setSubmitted(UUID id, String by);

    void returnToDraft(UUID id, String warning);

    void setApproved(UUID id, String by);

    void setFinalized(UUID id, String by, Instant at);

    void setPaid(UUID id, Instant at);

    /** Settled runs (FINALIZED or PAID) of the entity with period in [from, to]. */
    Map<YearMonth, UUID> settledRuns(String entity, YearMonth from, YearMonth to);

    // inputs and chunks
    void clearComputed(UUID runId);

    void insertInputs(UUID runId, Map<UUID, EmployeeSnapshot> snapshots);

    Map<UUID, EmployeeSnapshot> inputs(UUID runId, Collection<UUID> employees);

    Map<UUID, String> inputHashes(UUID runId);

    void insertChunks(UUID runId, List<List<UUID>> chunks);

    List<Chunk> chunks(UUID runId);

    void resetUnfinishedChunks(UUID runId);

    Optional<Chunk> claimChunk(UUID runId, int chunkNo);

    void chunkDone(UUID runId, int chunkNo);

    void chunkFailed(UUID runId, int chunkNo, String error);

    // payslips
    void savePayslip(UUID runId, UUID employee, Computation c, Map<String, BigDecimal> inputs, BigDecimal taxable,
                     BigDecimal tds, List<ArrearsLine> arrears);

    List<PayslipRow> payslips(UUID runId);

    Optional<PayslipRow> payslip(UUID runId, UUID employee);

    List<PayslipRow> settledPayslips(UUID employee);

    Map<UUID, Map<String, BigDecimal>> payslipAmounts(UUID runId, Collection<UUID> employees);

    List<ArrearsLine> arrears(UUID runId, UUID employee);

    /** Arrears already settled in FINALIZED/PAID runs, per employee, for-period and component. */
    Map<UUID, Map<String, Map<String, BigDecimal>>> settledArrears(Collection<UUID> employees);

    Map<UUID, Ytd> ytd(Collection<UUID> employees, String entity, YearMonth fromInclusive, YearMonth toExclusive);

    // attendance projection
    boolean upsertDay(UUID employee, LocalDate day, String status, BigDecimal payable, int version);

    Map<UUID, Map<LocalDate, BigDecimal>> payableDays(Collection<UUID> employees, LocalDate from, LocalDate to);

    // pdf
    void queuePdfJobs(UUID runId, Instant at);

    record PdfJob(UUID runId, UUID employeeId, int attempts) {
    }

    List<PdfJob> claimPdfJobs(int limit);

    void pdfDone(PdfJob job, String key, Instant at);

    void pdfFailed(PdfJob job, String error, boolean giveUp, Instant at);

    Map<String, Long> pdfStatus(UUID runId);

    // bank files
    Optional<BankFile> activeBankFile(UUID runId);

    int bankFileCount(UUID runId);

    boolean insertBankFile(BankFile f);

    void voidBankFile(UUID id);

    boolean insertReissue(Reissue r);

    Optional<Reissue> lockReissue(UUID id);

    void approveReissue(UUID id, String by);
}
