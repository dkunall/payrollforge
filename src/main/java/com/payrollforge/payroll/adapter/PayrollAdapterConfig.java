package com.payrollforge.payroll.adapter;

import com.payrollforge.payroll.application.PdfService;
import com.payrollforge.payroll.application.StructureService;
import com.payrollforge.shared.config.PayrollForgeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Configuration(proxyBeanMethods = false)
class PayrollAdapterConfig {

    @Bean(destroyMethod = "close")
    S3ObjectStore objectStore(PayrollForgeProperties props) {
        var cfg = props.storage();
        if (cfg.accessKey() == null || cfg.accessKey().isBlank() || cfg.secretKey() == null || cfg.secretKey().isBlank()) {
            throw new IllegalStateException("PAYROLLFORGE_S3_ACCESS_KEY and PAYROLLFORGE_S3_SECRET_KEY must be set");
        }
        var store = new S3ObjectStore(cfg);
        store.ensureBucket();
        return store;
    }

    /** Seeds the two illustrative structures from April 2025, so arrears can reach back a full year from 2026. */
    @Component
    static class Defaults {

        private final StructureService structures;

        Defaults(StructureService structures) {
            this.structures = structures;
        }

        @EventListener(ApplicationReadyEvent.class)
        void seed() {
            structures.ensureDefaults(LocalDate.of(2025, 4, 1));
        }
    }

    @Component
    @ConditionalOnProperty(name = "payrollforge.jobs.enabled", havingValue = "true", matchIfMissing = true)
    static class PdfWorker {

        private final PdfService pdfs;

        PdfWorker(PdfService pdfs) {
            this.pdfs = pdfs;
        }

        @Scheduled(fixedDelayString = "${payrollforge.jobs.pdf-poll:PT1S}")
        void tick() {
            while (pdfs.renderBatch(20) == 20) {
                // keep draining while full batches come back
            }
        }
    }
}
