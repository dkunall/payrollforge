package com.payrollforge.payroll.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrollforge.payroll.application.TaxDeclarationStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Repository
class JdbcTaxDeclarationStore implements TaxDeclarationStore {

    private final JdbcClient db;
    private final ObjectMapper json;

    JdbcTaxDeclarationStore(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Override
    public void upsert(Declaration d) {
        var items = new TreeMap<String, String>();
        d.items().forEach((k, v) -> items.put(k, v.toPlainString()));
        try {
            db.sql("""
                            insert into tax_declaration (employee_id, fy, regime, items, total, updated_at) values (?, ?, ?, ?::jsonb, ?, ?)
                            on conflict (employee_id, fy) do update set regime = excluded.regime, items = excluded.items,
                              total = excluded.total, updated_at = excluded.updated_at""")
                    .params(d.employeeId(), d.fy(), d.regime(), json.writeValueAsString(items), d.total(),
                            Timestamp.from(d.updatedAt()))
                    .update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<Declaration> find(UUID employee, String fy) {
        return db.sql("select * from tax_declaration where employee_id = ? and fy = ?").params(employee, fy)
                .query(this::map).optional();
    }

    @Override
    public Map<UUID, Declaration> forYear(Collection<UUID> employees, String fy) {
        var out = new HashMap<UUID, Declaration>();
        db.sql("select * from tax_declaration where employee_id = any(?) and fy = ?")
                .params(employees.toArray(UUID[]::new), fy).query(this::map).list()
                .forEach(d -> out.put(d.employeeId(), d));
        return out;
    }

    private Declaration map(ResultSet rs, int i) throws SQLException {
        Map<String, String> raw;
        try {
            raw = json.readValue(rs.getString("items"), new TypeReference<Map<String, String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        var items = new TreeMap<String, BigDecimal>();
        raw.forEach((k, v) -> items.put(k, new BigDecimal(v)));
        return new Declaration(rs.getObject("employee_id", UUID.class), rs.getString("fy"), rs.getString("regime"), items,
                rs.getBigDecimal("total"), rs.getTimestamp("updated_at").toInstant());
    }
}
