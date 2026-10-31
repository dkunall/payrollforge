package com.payrollforge.attendance.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrollforge.attendance.application.AttendanceService;
import com.payrollforge.leave.application.LeaveEvents;
import com.payrollforge.outbox.application.EventEnvelope;
import com.payrollforge.outbox.application.Outbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;

/** Re-closes already closed days that a newly approved leave covers. */
@Component
@ConditionalOnProperty(name = "payrollforge.jobs.enabled", havingValue = "true", matchIfMissing = true)
class LeaveApprovedListener {

    private final AttendanceService attendance;
    private final ObjectMapper json;

    LeaveApprovedListener(AttendanceService attendance, ObjectMapper json) {
        this.attendance = attendance;
        this.json = json;
    }

    @KafkaListener(topics = Outbox.LEAVE, groupId = "payrollforge-attendance")
    void on(String payload) throws IOException {
        EventEnvelope e = json.readValue(payload, EventEnvelope.class);
        if (LeaveEvents.APPROVED.equals(e.type())) {
            var d = e.data();
            attendance.onLeaveApproved(e.eventId(), UUID.fromString((String) d.get("employeeId")),
                    LocalDate.parse((String) d.get("from")), LocalDate.parse((String) d.get("to")));
        }
    }
}
