package com.ggu.gpu;

import com.ggu.config.GGUConfig;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BufferManager - SSBO/DMA memory management for GGU.
 * 
 * Manages GPU buffers (SSBO - Shader Storage Buffer Objects) and handles
 * zero-copy DMA transfer between GPU and JVM DirectByteBuffer memory spaces.
 * 
 * Key features:
 * - Double-buffered output for overlapping computation/dma
 * - Pinned host memory for fast transfers
 * - CompletableFuture-based async readback
 * - Zero-copy via DirectByteBuffer memory mapping
 */
public class BufferManager {
    
    // Buffer sizes (in bytes)
    private static final int BLOCK_STATE_SIZE = 16 * 384 * 16; // Total blocks per chunk
    private static final int VOXEL_SIZE = 4; // u32 per block state entry
    private static final int OUTPUT_BUFFER_SIZE = BLOCK_STATE_SIZE * VOXEL_SIZE;
    
    // Descriptor set binding points
    private static final int INPUT_BUFFER_BINDING = 0;
    private static final int OUTPUT_BUFFER_BINDING = 1;
    private static final int UNIFORM_BUFFER_BINDING = 2;
    
    // Vulkan buffer handles (via native)
    private long inputSSBO;       // VkBuffer for chunk task inputs
    private long[] outputSSBOs;   // VkBuffers for block state outputs (ping-pong pool)
    private long uniformBuffer;   // VkBuffer for generation parameters
    
    // Java-side DirectByteBuffer references (zero-copy bridge to GPU memory)
    private ByteBuffer inputHostBuffer;
    private ByteBuffer[] outputHostBuffers;
    
    // Double-buffering / ping-pong state
    private volatile int currentBufferIndex = 0;
    
    // Synchronization lock for buffer index management
    private final Object syncLock = new Object();
    
    // Task ID counter
    private final AtomicLong nextTaskId = new AtomicLong(0);
    
    // Background executor for DMA transfers
    private final ExecutorService dmaExecutor = Executors.newFixedThreadPool(2);
    
    // Configuration
    private final int poolSize;
    private final GGUConfig config;
    private final VulkanContext vulkanContext;
    
    // Performance metrics
    private long totalBytesTransferred = 0;
    private long totalReadbackTimeMs = 0;
    private int readbackCount = 0;
    
    public BufferManager(VulkanContext context) {
        this.vulkanContext = context;
        this.config = context.config();
        this.poolSize = Math.max(config.gpuQueueDepth(), 2); // At least 2 for ping-pong
        
        // Allocate SSBO handle arrays
        this.outputSSBOs = new long[poolSize];
        this.outputHostBuffers = new ByteBuffer[poolSize];
    }
    
    /**
     * Initialize buffer manager - allocate SSBOs in GPU memory.
     * 
     * Creates:
     * 1. Input SSBO (28 bytes * MAX_BATCH_COUNT for chunk task parameters)
     * 2. Output SSBO pool (ping-pong double buffering)
     * 3. Uniform buffer (generation parameters)
     */
    public boolean initialize() {
        long device = vulkanContext.getDeviceHandle();
        if (device == 0L) {
            System.err.println("[GGU] Cannot init BufferManager: invalid device");
            return false;
        }
        
        try {
            // 1. Allocate input SSBO (task parameter buffer)
            long inputSize = (long) GGUBatchTask.MAX_TASKS * 28L; // 28 bytes per task entry
            inputSSBO = nativeAllocBuffer(device, inputSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            
            if (inputSSBO == 0L) {
                System.err.println("[GGU] Failed to allocate input SSBO");
                return false;
            }
            
            // Map input buffer to host-accessible memory (for zero-copy writes)
            inputHostBuffer = nativeMapBufferToHost(inputSSBO, inputSize);
            
            // 2. Allocate output SSBOs (ping-pong double-buffered pool)
            for (int i = 0; i < poolSize; i++) {
                outputSSBOs[i] = nativeAllocBuffer(
                    device,
                    OUTPUT_BUFFER_SIZE,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
                );
                
                if (outputSSBOs[i] == 0L) {
                    System.err.println("[GGU] Failed to allocate output SSBO[" + i + "]");
                    return false;
                }
                
                // Map each output buffer to host memory for DMA readback
                outputHostBuffers[i] = nativeMapBufferToHost(outputSSBOs[i], OUTPUT_BUFFER_SIZE);
            }
            
            // 3. Allocate uniform buffer (generation parameters)
            uniformBuffer = nativeAllocBuffer(device, 256L, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            
            return true;
            
        } catch (Exception e) {
            System.err.println("[GGU] BufferManager init error: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get the current output SSBO pointer for GPU dispatch.
     * Uses ping-pong double buffering to overlap DMA readback with computation.
     */
    public long getOutputSSBO() {
        synchronized (syncLock) {
            return outputSSBOs[currentBufferIndex];
        }
    }
    
    /**
     * Swap buffers after dispatch - atomically advances to next buffer in pool.
     * Returns the index that will hold results from current dispatch.
     */
    public int swapBuffer() {
        synchronized (syncLock) {
            int prev = currentBufferIndex;
            currentBufferIndex = (currentBufferIndex + 1) % poolSize;
            return prev;
        }
    }
    
    /**
     * Readback processed data from GPU SSBO to host memory via DMA.
     * 
     * This is the critical zero-copy path:
     * - GPU writes results to outputSSBO in device memory
     * - vkMapMemory + vkCmdCopyBufferToBuffer initiates PCIe DMA transfer
     * - Data lands directly in Java ByteBuffer (no intermediate copies!)
     * 
     * @param gpuEvent GPU fence/event handle from compute dispatch
     * @param taskCount Number of chunks that were processed
     * @return CompletableFuture that completes when DMA transfer finishes
     */
    public CompletableFuture<ByteBuffer> readback(long gpuEvent, int taskCount) {
        // Determine which buffer holds the results
        int bufferIdx = swapBuffer();
        long bufferSize = (long) OUTPUT_BUFFER_SIZE * taskCount;
        
        // Create async readback future
        CompletableFuture<ByteBuffer> future = new CompletableFuture<>();
        
        // Schedule DMA transfer on background thread pool (never block server thread!)
        dmaExecutor.submit(() -> {
            long startTime = System.nanoTime();
            
            try {
                // 1. Wait for GPU compute to complete (non-blocking polling)
                boolean completed = false;
                long waited = 0;
                while (!completed && waited < 5000) { // max 5 second timeout
                    completed = nativePollFence(vulkanContext.getDeviceHandle(), gpuEvent);
                    if (!completed) {
                        Thread.yield();
                        waited += 1;
                    }
                }
                
                if (!completed) {
                    future.completeExceptionally(
                        new java.util.concurrent.TimeoutException("GPU readback timeout after " + waited + "ms")
                    );
                    return;
                }
                
                // 2. Initiate DMA transfer: GPU SSBO -> Java DirectByteBuffer
                ByteBuffer result = nativeDMAReadback(
                    vulkanContext.getDeviceHandle(),
                    outputSSBOs[bufferIdx],
                    OUTPUT_BUFFER_SIZE
                );
                
                long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
                
                // 3. Update performance metrics
                totalBytesTransferred += result.remaining();
                totalReadbackTimeMs += elapsedMs;
                readbackCount++;
                
                // Complete the future with results
                future.complete(result);
                
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }
    
    /**
     * Upload input task data to GPU SSBO.
     * 
     * Uses vkMapMemory for zero-copy host-to-device transfer.
     */
    public void uploadInput(ByteBuffer hostData) {
        if (inputHostBuffer == null) {
            throw new IllegalStateException("Input buffer not mapped");
        }
        
        // Direct memory copy via mapped pointer (vkMapMemory)
        nativeUploadToDevice(vulkanContext.getDeviceHandle(), inputSSBO, hostData, hostData.remaining());
    }
    
    /**
     * Update uniform buffer with generation parameters.
     */
    public void updateUniforms(int chunkX, int chunkZ, long seed) {
        ByteBuffer uniforms = ByteBuffer.allocate(256).order(java.nio.ByteOrder.nativeOrder());
        
        // Pack uniforms to match GGG_uniforms GLSL struct:
        uniforms.putInt(chunkX);       // [0:4]   chunkOffsetX
        uniforms.putInt(0);             // [4:8]   padding
        uniforms.putInt(chunkZ);        // [8:12]  chunkOffsetZ  
        uniforms.putInt(0);             // [12:16] padding
        uniforms.putLong(seed);         // [16:24] worldSeed
        uniforms.putFloat(0.005f);      // [24:28] noiseScale
        uniforms.putFloat(64.0f);       // [28:32] heightOffset
        uniforms.putInt(16);            // [32:36] chunkWidth (fixed 16 columns)
        uniforms.putInt(384);           // [36:40] chunkHeight (max build Y)
        uniforms.putInt(0);             // [40:44] padding
        uniforms.flip();
        
        nativeUploadToDevice(vulkanContext.getDeviceHandle(), uniformBuffer, uniforms, uniforms.remaining());
    }
    
    // --- Getters for GPU handles ---
    
    public long getInputSSBO() {
        return inputSSBO;
    }
    
    public long getUniformBuffer() {
        return uniformBuffer;
    }
    
    public int getCurrentBufferIndex() {
        return currentBufferIndex;
    }
    
    // --- Performance metrics ---
    
    public double getAverageReadbackTimeMs() {
        return readbackCount > 0 ? (double) totalReadbackTimeMs / readbackCount : 0.0;
    }
    
    public long getTotalBytesTransferred() {
        return totalBytesTransferred;
    }
    
    public int getBufferPoolSize() {
        return poolSize;
    }
    
    // --- Shutdown ---
    
    /**
     * Free all GPU-allocated buffers and cleanup host memory.
     */
    public void shutdown() {
        if (inputSSBO != 0L) {
            nativeFreeBuffer(vulkanContext.getDeviceHandle(), inputSSBO);
            inputSSBO = 0L;
        }
        
        for (int i = 0; i < poolSize; i++) {
            if (outputSSBOs[i] != 0L) {
                nativeFreeBuffer(vulkanContext.getDeviceHandle(), outputSSBOs[i]);
                outputSSBOs[i] = 0L;
            }
        }
        
        if (uniformBuffer != 0L) {
            nativeFreeBuffer(vulkanContext.getDeviceHandle(), uniformBuffer);
            uniformBuffer = 0L;
        }
        
        dmaExecutor.shutdown();
    }
    
    // --- Vulkan buffer constants ---
    
    private static final int VK_BUFFER_USAGE_STORAGE_BUFFER_BIT = 0x00000002;
    private static final int VK_BUFFER_USAGE_TRANSFER_SRC_BIT = 0x00000400;
    private static final int VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT = 0x00000004;
    
    // --- Native buffer management methods (implemented in C++) ---
    
    /**
     * Allocate a Vulkan buffer object on the GPU.
     */
    private native long nativeAllocBuffer(long device, long size, int usageFlags);
    
    /**
     * Free a Vulkan buffer object and release GPU memory.
     */
    private native void nativeFreeBuffer(long device, long buffer);
    
    /**
     * Map a buffer to host-accessible memory for DMA transfer.
     */
    private native ByteBuffer nativeMapBufferToHost(long buffer, long size);
    
    /**
     * Initiate PCIe DMA readback from GPU buffer to Java heap buffer.
     */
    private native ByteBuffer nativeDMAReadback(long device, long srcBuffer, int byteCount);
    
    /**
     * Upload host data to GPU device memory via vkCmdCopyBuffer.
     */
    private native void nativeUploadToDevice(long device, long destBuffer, ByteBuffer srcData, int byteCount);
    
    /**
     * Poll fence status without blocking (returns false if still pending).
     */
    private native boolean nativePollFence(long device, long fence);
}