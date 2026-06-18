package com.ggu.batching;

import com.ggu.config.GGUConfig;
import com.ggu.gpu.ComputePipeline;
import com.ggu.gpu.VulkanContext;
import com.ggu.native_.NativeBridge;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GGU_BatchDispatcher - Task batching mechanism for GPU chunk generation.
 * 
 * Collects pending chunk generation tasks until a threshold is met, then flushes
 * them to the GPU in a single batched dispatch. This maximizes GPU utilization
 * by processing multiple chunks per dispatch overhead.
 * 
 * Batch Processing Flow:
 * 1. Chunky requests noise generation for chunk (X,Z)
 * 2. GGU Mixin intercepts -> submits GGUNoiseTask to dispatcher queue
 * 3. Tasks accumulate until batchSize threshold (default: 64 chunks)
 * 4. Dispatcher serializes all tasks and dispatches to GPU simultaneously
 * 5. Results are assembled asynchronously via CompletableFuture
 */
public class GGU_BatchDispatcher {
    
    // Batch configuration constants
    private static final int DEFAULT_BATCH_SIZE = 64;        // Max chunks per batch
    private static final long FLUSH_INTERVAL_NS = 1_000_000_000L; // 1 second flush timeout
    private static final int TASK_HEADER_SIZE = 28;           // Serialized task size in bytes
    
    // Batching state
    private final List<GGUNoiseTask> pendingTasks = new ArrayList<>();
    private final Map<Long, CompletableFuture<ByteBuffer>> taskFutures = new ConcurrentHashMap<>();
    
    // Synchronization primitives
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private final Object batchLock = new Object();
    
    // GPU subsystem references
    private final VulkanContext vulkanContext;
    private final GGUConfig config;
    private final Thread dispatcherThread;
    private volatile boolean running = false;
    
    // Performance metrics
    private long totalBatchesProcessed = 0;
    private long totalChunksProcessed = 0;
    private long totalGPUDispatchTimeMs = 0;
    private long totalReadbackTimeMs = 0;
    
    public GGU_BatchDispatcher(NativeBridge nativeBridge, VulkanContext vulkanContext) {
        this.vulkanContext = vulkanContext;
        this.config = vulkanContext.config();
        this.dispatcherThread = new Thread(this::dispatcherLoop, "GGU-BatchDispatcher");
        this.dispatcherThread.setDaemon(true); // Don't prevent JVM shutdown
        this.dispatcherThread.setPriority(Thread.MAX_PRIORITY);
    }
    
    /**
     * Start the batch dispatcher background thread.
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        dispatcherThread.start();
    }
    
    /**
     * Stop the batch dispatcher and flush pending tasks.
     */
    public void stop() {
        running = false;
        
        // Flush any remaining tasks before shutdown
        synchronized (batchLock) {
            if (!pendingTasks.isEmpty()) {
                flushBatch();
            }
        }
        
        // Wait for dispatcher thread to finish
        try {
            dispatcherThread.join(5000); // Max 5 second wait
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Submit a chunk generation task to the batching queue.
     * 
     * This is the primary integration point with Chunky - called from the
     * GGU Mixin that intercepts NoiseChunkGenerator.
     * 
     * @param chunkX     World X coordinate of the chunk
     * @param chunkZ     World Z coordinate of the chunk
     * @param worldSeed  Minecraft world seed
     * @param biomeData  Biome buffer data from Chunky (optional)
     * @return CompletableFuture that completes when GPU processing finishes
     */
    public CompletableFuture<ByteBuffer> submitTask(
        int chunkX, int chunkZ, long worldSeed, ByteBuffer biomeData
    ) {
        // Create the noise task
        GGUNoiseTask task = new GGUNoiseTask(chunkX, chunkZ, worldSeed, biomeData);
        
        synchronized (batchLock) {
            pendingTasks.add(task);
            
            // Register future for later completion
            long taskKey = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
            taskFutures.put(taskKey, task.completionFuture);
            
            // Check if batch threshold reached -> flush immediately
            int batchSize = config.batchSize();
            if (pendingTasks.size() >= batchSize) {
                flushBatch();
            } else {
                // Schedule deferred flush in case tasks arrive slowly
                scheduleDeferredFlush();
            }
        }
        
        return task.completionFuture;
    }
    
    /**
     * Flush all pending tasks to GPU in a single batched dispatch.
     */
    private void flushBatch() {
        if (pendingTasks.isEmpty()) {
            return;
        }
        
        long dispatchStart = System.nanoTime();
        int taskCount = pendingTasks.size();
        
        // Serialize all tasks into contiguous input buffer
        ByteBuffer inputBuffer = ByteBuffer.allocate(taskCount * TASK_HEADER_SIZE)
            .order(java.nio.ByteOrder.nativeOrder());
        
        for (GGUNoiseTask task : pendingTasks) {
            inputBuffer.putLong(task.chunkX);     // [0:4]   chunkOffsetX
            inputBuffer.putLong(task.chunkZ);     // [4:12]  chunkOffsetZ (padded to long)
            inputBuffer.putLong(task.worldSeed);  // [12:20] worldSeed
            inputBuffer.putFloat(0.005f);         // [20:24] noiseScale (GGU default)
            inputBuffer.putFloat(64.0f);          // [24:28] heightOffset (GGU default)
        }
        inputBuffer.flip();
        
        // Submit batch to GPU compute pipeline
        long gpuFence = vulkanContext.getComputePipeline().dispatch(inputBuffer, taskCount);
        
        // Readback results asynchronously (non-blocking)
        CompletableFuture<ByteBuffer> readbackFuture = 
            vulkanContext.getBufferManager().readback(gpuFence, taskCount);
        
        // Complete individual task futures when DMA finishes
        readbackFuture.thenAccept(resultsBuffer -> {
            long readbackEnd = System.nanoTime();
            totalReadbackTimeMs += (readbackEnd - dispatchStart) / 1_000_000;
            
            // Parse results and complete each task's future
            parseResultsAndCompleteTasks(resultsBuffer, taskCount);
            
            // Update metrics
            synchronized (batchLock) {
                totalBatchesProcessed++;
                totalChunksProcessed += taskCount;
            }
        });
        
        // Clear pending tasks for this batch
        synchronized (batchLock) {
            pendingTasks.clear();
        }
    }
    
    /**
     * Parse GPU results and complete individual task futures.
     * 
     * Output buffer layout per chunk:
     * [0:4]   chunkX (signed int32 - world coordinate)
     * [4:8]   chunkZ (signed int32 - world coordinate)
     * [8:65544] blockStates (16*384*16 u32 entries)
     */
    private void parseResultsAndCompleteTasks(ByteBuffer results, int taskCount) {
        for (int i = 0; i < taskCount; i++) {
            long chunkX = results.getLong(i * 256 * 1024); // Skip to next chunk's header
            long chunkZ = results.getLong((i * 256 * 1024) + 8);
            
            // Find matching future and complete with data slice
            long taskKey = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
            CompletableFuture<ByteBuffer> future = taskFutures.remove(taskKey);
            
            if (future != null && !future.isDone()) {
                // Slice the results buffer for this specific chunk
                int chunkDataOffset = i * OUTPUT_CHUNK_STRIDE;
                ByteBuffer chunkData = results.slice();
                chunkData.position(chunkDataOffset);
                chunkData.limit(chunkDataOffset + OUTPUT_CHUNK_DATA_SIZE);
                
                future.complete(chunkData);
            }
        }
    }
    
    /**
     * Schedule a deferred flush if no tasks have been added recently.
     * This handles the case where task arrivals slow down below batch size.
     */
    private void scheduleDeferredFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            // Schedule background flush after timeout
            new Thread(() -> {
                try {
                    Thread.sleep(50); // 50ms debounce interval
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                synchronized (batchLock) {
                    if (!pendingTasks.isEmpty()) {
                        flushBatch();
                    }
                    flushScheduled.set(false);
                }
            }).start();
        }
    }
    
    /**
     * Background dispatcher loop that monitors pending tasks.
     */
    private void dispatcherLoop() {
        while (running) {
            synchronized (batchLock) {
                if (!pendingTasks.isEmpty()) {
                    flushBatch();
                } else {
                    try {
                        batchLock.wait(100); // Wait 100ms for new tasks
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }
    }
    
    // --- Getters & Metrics ---
    
    /**
     * Get number of currently pending tasks.
     */
    public int getPendingTaskCount() {
        synchronized (batchLock) {
            return pendingTasks.size();
        }
    }
    
    /**
     * Get average batch size over all processed batches.
     */
    public double getAverageBatchSize() {
        synchronized (batchLock) {
            return totalBatchesProcessed > 0 
                ? (double) totalChunksProcessed / totalBatchesProcessed 
                : 0.0;
        }
    }
    
    /**
     * Get GPU processing time metrics.
     */
    public double getAverageGPUDispatchTimeMs() {
        synchronized (batchLock) {
            return totalBatchesProcessed > 0 
                ? (double) totalGPUDispatchTimeMs / totalBatchesProcessed 
                : 0.0;
        }
    }
    
    /**
     * Get DMA readback time metrics.
     */
    public double getAverageReadbackTimeMs() {
        synchronized (batchLock) {
            return totalBatchesProcessed > 0 
                ? (double) totalReadbackTimeMs / totalBatchesProcessed 
                : 0.0;
        }
    }
    
    // --- Constants for output buffer layout ---
    
    private static final int OUTPUT_CHUNK_STRIDE = 256 * 1024; // 256KB per chunk output
    private static final int OUTPUT_CHUNK_DATA_SIZE = 16 * 384 * 16 * 4; // 3.9MB per chunk
    
    /**
     * GGUNoiseTask - represents a single chunk noise generation request.
     */
    public static class GGUNoiseTask {
        public final int chunkX;
        public final int chunkZ;
        public final long worldSeed;
        public final ByteBuffer biomeData;
        public final CompletableFuture<ByteBuffer> completionFuture;
        
        public GGUNoiseTask(int chunkX, int chunkZ, long worldSeed, ByteBuffer biomeData) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.worldSeed = worldSeed;
            this.biomeData = biomeData;
            this.completionFuture = new CompletableFuture<>();
        }
    }
}