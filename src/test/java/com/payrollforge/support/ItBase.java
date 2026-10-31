package com.payrollforge.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.payrollforge.payroll.application.ChunkHook;
import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * The whole app on a random port against real Postgres, Kafka and RustFS, one shared Spring context. Tests create
 * their own employees with random codes, so they never assert on each other's people.
 */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ItBase.TestBeans.class)
public abstract class ItBase {

    public static final String HR = "hr@it.example.test";
    public static final String MAKER = "maker@it.example.test";
    public static final String CHECKER = "checker@it.example.test";
    /** Holds both payroll roles, to prove four-eyes is about people, not roles. */
    public static final String CHECKER_2 = "checker2@it.example.test";
    public static final String DEVICE = "device@it.example.test";

    /** Chunks listed here fail once, then succeed on the next start. */
    public static class FailOnce implements ChunkHook {
        public final Set<String> armed = ConcurrentHashMap.newKeySet();

        @Override
        public void beforeChunk(UUID runId, int chunkNo) {
            if (armed.remove(runId + ":" + chunkNo) || armed.remove("*:" + chunkNo)) {
                throw new IllegalStateException("injected failure in chunk " + chunkNo);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwt.decoder();
        }

        @Bean
        FailOnce failOnce() {
            return new FailOnce();
        }
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient db;

    @Autowired
    protected FailOnce failOnce;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        var pg = Containers.postgres();
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", () -> Containers.DB_PASSWORD);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
        r.add("spring.kafka.bootstrap-servers", () -> Containers.kafka().getBootstrapServers());
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://test-issuer/default");
        r.add("payrollforge.storage.endpoint", Containers::s3Endpoint);
        r.add("payrollforge.storage.access-key", () -> Containers.S3_ACCESS);
        r.add("payrollforge.storage.secret-key", () -> Containers.S3_SECRET);
        r.add("payrollforge.crypto.field-key", () -> Containers.FIELD_KEY);
        r.add("payrollforge.security.hr-subjects", () -> HR);
        r.add("payrollforge.security.maker-subjects", () -> MAKER + "," + CHECKER_2);
        r.add("payrollforge.security.checker-subjects", () -> CHECKER + "," + CHECKER_2);
        r.add("payrollforge.security.device-subjects", () -> DEVICE);
        r.add("payrollforge.payroll.chunk-size", () -> "4");
        r.add("payrollforge.outbox.poll", () -> "PT0.1S");
        r.add("payrollforge.jobs.pdf-poll", () -> "PT0.3S");
    }

    protected Api anon() {
        return Api.at(port);
    }

    protected Api as(String subject) {
        return Api.at(port).as(TestJwt.user(subject));
    }

    protected Api hr() {
        return as(HR);
    }

    protected Api maker() {
        return as(MAKER);
    }

    protected Api checker() {
        return as(CHECKER);
    }

    protected static String tag() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    /** A location with Sunday off, created once per code. */
    protected String location(String code, double lat, double lng) {
        var r = hr().post("/v1/locations", Map.of("code", code, "name", "Office " + code, "timezone", "Asia/Kolkata",
                "lat", lat, "lng", lng, "radiusMetres", 200, "weeklyOff", List.of(DayOfWeek.SUNDAY)));
        if (r.status() != 201 && r.status() != 409) {
            r.expect(201);
        }
        return code;
    }

    protected String defaultLocation() {
        return location("BLR1", 12.9716, 77.5946);
    }

    public record Person(String id, String code, String subject, Api api) {
    }

    protected Person employee(LocalDate joined, String ctc, String structure, String managerId) {
        String t = tag();
        String code = "E" + t;
        String subject = "emp-" + t.toLowerCase() + "@it.example.test";
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", "Test Person " + t);
        b.put("email", subject);
        b.put("subject", subject);
        b.put("joinedOn", joined.toString());
        b.put("bankAccount", "9" + String.format("%011d", Math.abs(t.hashCode()) % 100_000_000_000L));
        b.put("bankIfsc", "SYNT0000001");
        b.put("pan", "ABCDE" + String.format("%04d", Math.abs(t.hashCode()) % 10000) + "F");
        b.put("grade", "L2");
        b.put("dept", "ENG");
        b.put("location", defaultLocation());
        b.put("ctcAnnual", new BigDecimal(ctc));
        b.put("structureCode", structure);
        if (managerId != null) {
            b.put("managerId", managerId);
        }
        String id = hr().post("/v1/employees", b).expect(201).body().path("id").asText();
        return new Person(id, code, subject, as(subject));
    }

    protected Person employee(LocalDate joined, String ctc) {
        return employee(joined, ctc, "STD", null);
    }

    protected static void eventually(BooleanSupplier check) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(150))
                .until(check::getAsBoolean);
    }

    protected long count(String sql, Object... params) {
        return db.sql(sql).params(params).query(Long.class).single();
    }

    protected static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (n.path(field).asText().equals(value)) {
                return n;
            }
        }
        throw new AssertionError("no element with " + field + "=" + value + " in " + array);
    }
}
