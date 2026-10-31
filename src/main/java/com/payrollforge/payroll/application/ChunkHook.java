package com.payrollforge.payroll.application;

import java.util.UUID;

/** Called before a chunk computes; tests use it to inject failures. No bean is registered by default. */
public interface ChunkHook {

    void beforeChunk(UUID runId, int chunkNo);
}
