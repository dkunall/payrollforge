package com.payrollforge.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("payrollforge")
public record PayrollForgeProperties(Security security, Crypto crypto, Storage storage, Attendance attendance,
                                     Approvals approvals, Payroll payroll, Checklists checklists, Jobs jobs) {

    /** JWT subjects per staff role. One subject may hold several roles, but maker and checker must differ per run. */
    public record Security(List<String> hrSubjects, List<String> makerSubjects, List<String> checkerSubjects,
                           List<String> deviceSubjects) {
    }

    /** @param fieldKey base64 AES-256 key for bank account and PAN columns; never logged */
    public record Crypto(String fieldKey) {
    }

    /** S3-compatible object storage for payslip PDFs and bank files. */
    public record Storage(URI endpoint, String region, String bucket, String accessKey, String secretKey) {
    }

    /**
     * @param fullDayMinutes worked minutes for a full day; at least {@code halfDayMinutes} is a half day
     * @param futureSkew     punches further in the future than this are rejected (device clock drift)
     * @param maxAge         punches older than this are rejected
     */
    public record Attendance(int fullDayMinutes, int halfDayMinutes, Duration futureSkew, Duration maxAge,
                             int batchMax) {
    }

    /** HR approves leave longer than {@code leaveHrAboveDays} and claims above {@code claimHrAboveAmount}. */
    public record Approvals(BigDecimal leaveHrAboveDays, BigDecimal claimHrAboveAmount) {
    }

    /**
     * @param entity                legal entity code; runs are unique per (entity, period)
     * @param chunkSize             employees per compute chunk
     * @param parallelism           chunks computed at once (virtual threads, bounded by a semaphore)
     * @param outlierRatio          a net-pay change above this share of the earlier net is flagged in diffs
     * @param arrearsLookbackMonths how many settled periods are re-checked for retro changes
     * @param taxRules              illustrative slab tables; snapshotted into every run
     */
    public record Payroll(String entity, int chunkSize, int parallelism, BigDecimal outlierRatio,
                          int arrearsLookbackMonths, TaxRulesConfig taxRules) {
    }

    public record TaxRulesConfig(String id, Map<String, RegimeConfig> regimes) {
    }

    public record RegimeConfig(BigDecimal standardDeduction, BigDecimal declarationCap, BigDecimal cessRate,
                               List<SlabConfig> slabs) {
    }

    public record SlabConfig(BigDecimal from, BigDecimal upTo, BigDecimal rate) {
    }

    public record Checklists(List<String> onboarding, List<String> offboarding) {
    }

    public record Jobs(boolean enabled, Duration pdfPoll) {
    }
}
