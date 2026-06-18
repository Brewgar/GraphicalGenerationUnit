package com.ggu.native_;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * Native Bridge for GGU - JNI/Panama Foreign Function API.
 * 
 * Provides the communication layer between Java and the native Vulkan compute system.
 * Uses Panama Foreign Function API (JEP 454) for modern JNI alternatives with
 * automatic memory management and zero-copy data transfer.
 */
public class NativeBridge {
    
    private static final int CHUNK_DATA_SIZE = 16 * 384 * 16 * 4; // x,y,z block states (u32 each)
    private static final int MAX_BATCH_COUNT = 64;
    private static NativeBridge instance;
    
    // Native context handle
    private long nativeCtx;
    private boolean initialized = false;
    
    /**
     * Get singleton instance of NativeBridge.
     */
    public static NativeBridge getInstance() throws UnsatisfiedLinkError {
        if (instance == null) {
            instance = new NativeBridge();
        }
        return instance;
    }
    
    private NativeBridge() {
        // Load native library
        System.loadLibrary("ggu_native");
    }
    
    /**
     * Initialize the native GGU context.
     */
    public void initialize() throws NativeInitException {
        try {
            nativeCtx = nativeGGUInit();
            if (nativeCtx == 0L) {
                throw new NativeInitException("Failed to initialize native GGU context");
            }
            initialized = true;
        } catch (UnsatisfiedLinkError e) {
            throw new NativeInitException("Native library not found: " + e.getMessage());
        }
    }
    
    /**
     * GGU Task submitted for GPU processing.
     */
    public GGUChunkTask createChunkTask(int chunkX, int chunkZ, long seed, ByteBuffer biomeData) {
        return new GGUChunkTask(chunkX, chunkZ, seed, biomeData);
    }
    
    // --- Native method declarations ---
    
    /**
     * Initialize native GGU context.
     * @return native context pointer or 0 on failure
     */
    private static native long nativeGGUInit();
    
    /**
     * Dispatch a batch of chunks to the GPU for processing.
     * @param ctx native context
     * @param inputBuffer raw buffer containing packed GGUChunkTask structures
     * @param taskCount number of tasks in the batch
     * @return GPU event/fence handle for synchronization
     */
    public native long nativeDispatchBatch(long ctx, ByteBuffer inputBuffer, int taskCount);
    
    /**
     * Wait for GPU processing to complete. Non-blocking: returns false if still processing.
     */
    public native boolean nativeWaitForCompletion(long ctx, long timeoutMs);
    
    /**
     * Readback processed data from GPU SSBO to host memory.
     */
    public native ByteBuffer nativeReadbackSSBO(long ctx, int byteCount);
    
    /**
     * Free native-allocated buffer.
     */
    public native void nativeFree(long pointer);
    
    /**
     * Get the current GPU device name via JNI.
     */
    public native String nativeGetGPUName();
    
    /**
     * GGUChunkTask - represents a single chunk generation task for the GPU.
     * Memory layout: packed struct matching C++ NativeChunkTask
     */
    public class GGUChunkTask {
        public final int chunkX;
        public final int chunkZ;
        public final long seed;
        public final ByteBuffer biomeData;
        public CompletableFuture<ByteBuffer> completionFuture;
        public boolean submitted = false;
        
        public GGUChunkTask(int chunkX, int chunkZ, long seed, ByteBuffer biomeData) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.seed = seed;
            this.biomeData = biomeData;
            this.completionFuture = new CompletableFuture<>();
        }
        
        /**
         * Serialize task to native-compatible buffer layout.
         * 
         * Layout (28 bytes per task):
         * [0:4]   chunkX (int32)
         * [4:8]   chunkZ (int32)
         * [8:16]  seed (int64)
         * [16:20] noiseScale (float32, hardcoded to 0.005 in GGU default)
         * [20:24] heightOffset (float32, hardcoded to 64.0f in GGU default)
         * [24:28] flags (int32 - biome mode flags)
         */
        public ByteBuffer serialize() {
            ByteBuffer buf = ByteBuffer.allocate(28).order(java.nio.ByteOrder.nativeOrder());
            buf.putInt(chunkX);
            buf.putInt(chunkZ);
            buf.putLong(seed);
            buf.putFloat(0.005f);  // noiseScale (GGU default)
            buf.putFloat(64.0f);   // heightOffset (GGU default)
            buf.putInt(biomeData != null ? biomeData.remaining() : 0);
            buf.flip();
            return buf;
        }
    }
    
    /**
     * Exception thrown when native initialization fails.
     */
    public static class NativeInitException extends Exception {
        public NativeInitException(String message) {
            super(message);
        }
    }
    
    // --- Static convenience methods for GGU integration with Chunky ---
    
    /**
     * Check if the native library is loaded and valid.
     */
    public boolean isValid() {
        return initialized && nativeCtx != 0L;
    }
}