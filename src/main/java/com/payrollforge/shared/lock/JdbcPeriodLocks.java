package com.payrollforge.shared.lock;

import com.payrollforge.shared.domain.DomainConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

@Repository
class JdbcPeriodLocks implements PeriodLocks {

    private final JdbcClient db;

    JdbcPeriodLocks(JdbcClient db) {
        this.db = db;
    }

    @Override
    public boolean isLocked(YearMonth period) {
        return db.sql("select count(*) from payroll_lock where period = ?").param(period.toString())
                .query(Long.class).single() > 0;
    }

    @Override
    public void requireOpen(LocalDate from, LocalDate to) {
        if (anyLocked(from, to)) {
            throw new DomainConflictException("payroll-locked",
                    "Payroll for a period covering " + from + ".." + to + " is approved; these days can no longer change");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(YearMonth period, UUID runId) {
        db.sql("insert into payroll_lock (period, run_id, locked_at) values (?, ?, now()) on conflict (period) do nothing")
                .params(period.toString(), runId).update();
    }
}
