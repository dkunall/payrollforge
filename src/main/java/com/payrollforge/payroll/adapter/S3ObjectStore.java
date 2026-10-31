package com.payrollforge.payroll.adapter;

import com.payrollforge.payroll.application.ObjectStore;
import com.payrollforge.shared.config.PayrollForgeProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Duration;
import java.util.Optional;

/** S3 adapter tested against RustFS: path-style addressing, checksums only where an operation requires them. */
class S3ObjectStore implements ObjectStore, AutoCloseable {

    private final S3Client s3;
    private final String bucket;

    S3ObjectStore(PayrollForgeProperties.Storage cfg) {
        this.bucket = cfg.bucket();
        this.s3 = S3Client.builder()
                .endpointOverride(cfg.endpoint())
                .region(Region.of(cfg.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.accessKey(), cfg.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClient(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(30)).build())
                .build();
    }

    void ensureBucket() {
        try {
            s3.headBucket(r -> r.bucket(bucket));
        } catch (NoSuchBucketException e) {
            s3.createBucket(r -> r.bucket(bucket));
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            s3.createBucket(r -> r.bucket(bucket));
        }
    }

    @Override
    public void put(String key, byte[] data, String contentType) {
        s3.putObject(r -> r.bucket(bucket).key(key).contentType(contentType).contentLength((long) data.length),
                RequestBody.fromBytes(data));
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(s3.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        s3.close();
    }
}
