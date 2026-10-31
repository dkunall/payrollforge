package com.payrollforge.payroll.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrollforge.attendance.application.AttendanceEvents;
import com.payrollforge.outbox.application.EventEnvelope;
import com.payrollforge.outbox.application.Outbox;
import com.payrollforge.payroll.application.PayrollStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Payroll's own copy of closed attendance days. Days carry a version, and an older version never overwrites a
 * newer one, so redelivery and reordering within a partition are both harmless.
 */
@Component
@ConditionalOnProperty(name = "payrollforge.jobs.enabled", havingValue = "true", matchIfMissing = true)
class AttendanceDayListener {

    private final PayrollStore store;
    private final ObjectMapper json;

    AttendanceDayListener(PayrollStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    @KafkaListener(topics = Outbox.ATTENDANCE, groupId = "payrollforge-payroll", concurrency = "3")
    void on(String payload) throws IOException {
        EventEnvelope e = json.readValue(payload, EventEnvelope.class);
        if (AttendanceEvents.DAY_CLOSED.equals(e.type())) {
            var d = e.data();
            store.upsertDay(UUID.fromString((String) d.get("employeeId")), LocalDate.parse((String) d.get("day")),
                    (String) d.get("status"), new BigDecimal(d.get("payable").toString()),
                    Integer.parseInt(d.get("version").toString()));
        }
    }
}
