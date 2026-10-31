package com.payrollforge.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** One of each dependency per JVM, started on first use. Credentials are random per run and never printed. */
public final class Containers {

    public static final String DB_PASSWORD = hex(18);
    public static final String S3_ACCESS = "it" + hex(8);
    public static final String S3_SECRET = hex(20);
    public static final String FIELD_KEY = Base64.getEncoder().encodeToString(bytes(32));

    private static PostgreSQLContainer<?> postgres;
    private static KafkaContainer kafka;
    private static GenericContainer<?> s3;

    private Containers() {
    }

    public static synchronized PostgreSQLContainer<?> postgres() {
        if (postgres == null) {
            var pg = new PostgreSQLContainer<>("postgres:17-alpine").withDatabaseName("payrollforge")
                    .withUsername("payrollforge").withPassword(DB_PASSWORD)
                    .withCommand("postgres", "-c", "max_connections=200");
            pg.start();
            postgres = pg;
        }
        return postgres;
    }

    public static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            var k = new KafkaContainer("apache/kafka:3.8.0").withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx256m");
            k.start();
            kafka = k;
        }
        return kafka;
    }

    public static synchronized GenericContainer<?> s3() {
        if (s3 == null) {
            GenericContainer<?> c = new GenericContainer<>("rustfs/rustfs:1.0.4").withExposedPorts(9000)
                    .withEnv("RUSTFS_ACCESS_KEY", S3_ACCESS).withEnv("RUSTFS_SECRET_KEY", S3_SECRET)
                    .waitingFor(Wait.forListeningPort());
            c.start();
            s3 = c;
        }
        return s3;
    }

    public static String s3Endpoint() {
        return "http://" + s3().getHost() + ":" + s3().getMappedPort(9000);
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static String hex(int n) {
        return HexFormat.of().formatHex(bytes(n));
    }
}
