package com.payrollforge.org.adapter;

import com.payrollforge.org.application.Location;
import com.payrollforge.org.application.OrgStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
class JdbcOrgStore implements OrgStore {

    private final JdbcClient db;

    JdbcOrgStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public boolean insertLocation(Location l) {
        return db.sql("""
                        insert into location (code, name, timezone, lat, lng, radius_m, weekly_off)
                        values (?, ?, ?, ?, ?, ?, string_to_array(?, ',')) on conflict do nothing""")
                .params(l.code(), l.name(), l.timezone().getId(), l.lat(), l.lng(), l.radiusMetres(),
                        String.join(",", l.weeklyOff().stream().map(DayOfWeek::name).sorted().toList()))
                .update() == 1;
    }

    @Override
    public Optional<Location> location(String code) {
        return db.sql("select * from location where code = ?").param(code).query(JdbcOrgStore::map).optional();
    }

    @Override
    public List<Location> locations() {
        return db.sql("select * from location order by code").query(JdbcOrgStore::map).list();
    }

    @Override
    public void upsertHoliday(String location, LocalDate day, String name) {
        db.sql("insert into holiday (location_code, day, name) values (?, ?, ?) on conflict (location_code, day) do update set name = excluded.name")
                .params(location, day, name).update();
    }

    @Override
    public Map<LocalDate, String> holidays(String location) {
        var out = new LinkedHashMap<LocalDate, String>();
        db.sql("select day, name from holiday where location_code = ? order by day").param(location)
                .query(rs -> {
                    out.put(rs.getObject(1, LocalDate.class), rs.getString(2));
                });
        return out;
    }

    private static Location map(ResultSet rs, int i) throws SQLException {
        Array a = rs.getArray("weekly_off");
        var off = EnumSet.noneOf(DayOfWeek.class);
        Arrays.stream((String[]) a.getArray()).forEach(s -> off.add(DayOfWeek.valueOf(s)));
        return new Location(rs.getString("code"), rs.getString("name"), ZoneId.of(rs.getString("timezone")),
                rs.getDouble("lat"), rs.getDouble("lng"), rs.getInt("radius_m"), off);
    }
}
