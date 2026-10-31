package com.payrollforge.payroll.application;

import java.util.Optional;

public interface ObjectStore {

    void put(String key, byte[] data, String contentType);

    Optional<byte[]> get(String key);
}
