package com.payrollforge.payroll.adapter;

import com.payrollforge.payroll.application.StructureStore;
import com.payrollforge.payroll.domain.run.StructureSpec;
import com.payrollforge.payroll.domain.structure.ComponentDef;
import com.payrollforge.payroll.domain.structure.ComponentKind;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
class JdbcStructureStore implements StructureStore {

    private final JdbcClient db;

    JdbcStructureStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public int latestVersion(String code) {
        return db.sql("select coalesce(max(version), 0) from salary_structure where code = ?").param(code)
                .query(Integer.class).single();
    }

    @Override
    public boolean insert(StructureSpec spec, LocalDate effectiveFrom, Map<String, Set<String>> dependsOn, String by,
                          Instant at) {
        int n = db.sql("""
                        insert into salary_structure (code, version, effective_from, created_by, created_at)
                        values (?, ?, ?, ?, ?) on conflict do nothing""")
                .params(spec.code(), spec.version(), effectiveFrom, by, Timestamp.from(at)).update();
        if (n == 0) {
            return false;
        }
        int ordinal = 0;
        for (ComponentDef c : spec.components()) {
            db.sql("""
                            insert into salary_component (structure_code, structure_version, ordinal, code, label, kind, formula, depends_on, taxable)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(spec.code(), spec.version(), ordinal++, c.code(), c.label(), c.kind().name(), c.formula(),
                            dependsOn.getOrDefault(c.code(), Set.of()).stream().sorted().toArray(String[]::new),
                            c.taxable())
                    .update();
        }
        return true;
    }

    @Override
    public List<Version> versions(String code) {
        var heads = db.sql("select version, effective_from, created_by, created_at from salary_structure where code = ? order by version")
                .param(code)
                .query((rs, i) -> new Object[]{rs.getInt(1), rs.getObject(2, LocalDate.class), rs.getString(3),
                        rs.getTimestamp(4).toInstant()})
                .list();
        var out = new ArrayList<Version>();
        for (Object[] h : heads) {
            out.add(new Version(spec(code, (Integer) h[0]), (LocalDate) h[1], (String) h[2], (Instant) h[3]));
        }
        return out;
    }

    @Override
    public List<String> codes() {
        return db.sql("select distinct code from salary_structure order by code").query(String.class).list();
    }

    @Override
    public Optional<StructureSpec> effectiveOn(String code, LocalDate day) {
        return db.sql("select max(version) from salary_structure where code = ? and effective_from <= ?")
                .params(code, day).query(Integer.class).optional().map(v -> spec(code, v));
    }

    private StructureSpec spec(String code, int version) {
        var comps = db.sql("select * from salary_component where structure_code = ? and structure_version = ? order by ordinal")
                .params(code, version)
                .query((rs, i) -> new ComponentDef(rs.getString("code"), rs.getString("label"),
                        ComponentKind.valueOf(rs.getString("kind")), rs.getString("formula"), rs.getBoolean("taxable")))
                .list();
        return new StructureSpec(code, version, comps);
    }
}
