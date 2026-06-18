package com.ggu.gpu;

import com.ggu.config.GGUConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * ComputePipeline - Vulkan compute pipeline setup and dispatch for GGU.
 * 
 * Manages:
 * - Compute shader module loading (compiled from GLSL)
 * - Pipeline layout and descriptor set management
 * - Command buffer allocation and submission
 * - GPU event synchronization
 */
public class ComputePipeline {
    
    // Work group dimensions (must match GLSL specialization constants)
    private static final int WORKGROUP_X = 8;
    private static final int WORKGROUP_Y = 8;
    private static final int WORKGROUP_Z = 1;
    
    // Minecraft chunk dimensions
    private static final int CHUNK_WIDTH = 16;   // columns per chunk
    private static final int CHUNK_HEIGHT = 384;  // max build height (Y)
    
    // Descriptor set binding points
    private static final int INPUT_BUFFER_BINDING = 0;   // SSBO: chunk task inputs
    private static final int OUTPUT_BUFFER_BINDING = 1;  // SSBO: generated block states
    private static final int UNIFORM_BUFFER_BINDING = 2; // UBO: generation parameters
    
    // Vulkan handles (stored as longs for native interop)
    private long device;            // VkDevice
    private long computeQueue;      // VkQueue (compute queue)
    private long shaderModule;      // VkShaderModule
    private long pipelineLayout;    // VkPipelineLayout
    private long computePipeline;   // VkComputePipeline
    
    // Command buffers (pooled for batch dispatch)
    private final Queue<Long> commandBuffers = new ConcurrentLinkedQueue<>();
    
    // GPU event/fence for completion synchronization
    private long gpuEvent;
    
    private boolean initialized = false;
    private final VulkanContext vulkanContext;
    private final GGUConfig config;
    
    // Pre-compiled SPIR-V shader binary
    private byte[] computeShaderSPIRV;
    
    public ComputePipeline(VulkanContext context, GGUConfig config) {
        this.vulkanContext = context;
        this.config = config;
    }
    
    /**
     * Initialize the compute pipeline.
     * 
     * Pipeline setup steps:
     * 1. Load compiled SPIR-V shader from resources
     * 2. Create shader module via vkCreateShaderModule
     * 3. Create pipeline layout with descriptor set layouts
     * 4. Create compute pipeline via vkCreateComputePipelines
     */
    public boolean initialize() {
        try {
            // Load SPIR-V shader binary from classpath or config directory
            computeShaderSPIRV = loadSPIRVShader("ggu/shaders/noise_compute.spv");
            if (computeShaderSPIRV == null || computeShaderSPIRV.length == 0) {
                System.err.println("[GGU] Failed to load compute shader SPIR-V");
                return false;
            }
            
            // Get device handle from VulkanContext
            device = vulkanContext.getDeviceHandle();
            if (device == 0L) {
                System.err.println("[GGU] Cannot initialize pipeline: invalid device");
                return false;
            }
            
            // Create shader module from SPIR-V binary
            shaderModule = nativeCreateShaderModule(device, computeShaderSPIRV);
            if (shaderModule == 0L) {
                System.err.println("[GGU] Failed to create shader module");
                return false;
            }
            
            // Create pipeline layout with descriptor set bindings for SSBOs
            pipelineLayout = nativeCreatePipelineLayout(device);
            if (pipelineLayout == 0L) {
                System.err.println("[GGU] Failed to create pipeline layout");
                return false;
            }
            
            // Create compute pipeline
            computePipeline = nativeCreateComputePipeline(device, shaderModule, pipelineLayout);
            if (computePipeline == 0L) {
                System.err.println("[GGU] Failed to create compute pipeline");
                return false;
            }
            
            // Create synchronization primitives (fence + event)
            gpuEvent = nativeCreateDeviceEvent(device);
            if (gpuEvent == 0L) {
                System.err.println("[GGU] Failed to create GPU event");
                return false;
            }
            
            initialized = true;
            return true;
            
        } catch (IOException e) {
            System.err.println("[GGU] ComputePipeline init error: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Load compiled SPIR-V shader from classpath resources.
     * 
     * Search order:
     * 1. Classpath (embedded JAR)
     * 2. Config directory (for hot-reload during development)
     */
    private byte[] loadSPIRVShader(String resourcePath) throws IOException {
        // Try loading from embedded resources (compiled into JAR)
        try (var stream = ComputePipeline.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (stream != null) {
                System.out.println("[GGU] Loaded SPIR-V from classpath: " + resourcePath);
                return stream.readAllBytes();
            }
        }
        
        // Fall back to file path in config directory
        Path spvPath = Paths.get("config", "ggu", "shaders", resourcePath);
        if (Files.exists(spvPath)) {
            System.out.println("[GGU] Loaded SPIR-V from disk: " + spvPath);
            return Files.readAllBytes(spvPath);
        }
        
        System.err.println("[GGU] SPIR-V not found at: " + resourcePath);
        return null;
    }
    
    /**
     * Dispatch a batch of chunk generation tasks to the GPU.
     * 
     * Workgroup dimensions are calculated per-chunk:
     * - X: ceil(16/8) = 2 workgroups (columns)
     * - Y: ceil(384/8) = 48 workgroups (height layers)
     * - Z: 1 (single layer for compute pass)
     * 
     * @param taskBuffer Buffer containing serialized GGUChunkTask entries
     * @param taskCount  Number of chunks in the batch
     * @return GPU fence handle for synchronization
     */
    public long dispatch(ByteBuffer taskBuffer, int taskCount) {
        if (!initialized) {
            throw new IllegalStateException("ComputePipeline not initialized");
        }
        
        // Calculate workgroup dimensions per chunk
        int workgroupsX = (CHUNK_WIDTH + WORKGROUP_X - 1) / WORKGROUP_X; // = 2
        int workgroupsY = (CHUNK_HEIGHT + WORKGROUP_Y - 1) / WORKGROUP_Y; // = 48
        int workgroupsZ = WORKGROUP_Z;
        
        // Get output SSBO from buffer manager (ping-pong double buffering)
        long outputSSBO = vulkanContext.getBufferManager().getOutputSSBO();
        
        // Submit compute dispatch via native layer
        long fence = nativeSubmitComputeDispatch(
            device,
            computePipeline,
            pipelineLayout,
            taskBuffer,          // input SSBO pointer (binding 0)
            outputSSBO,          // output SSBO pointer (binding 1)
            taskCount,           // number of chunk tasks
            workgroupsX,         // WG x-dimension
            workgroupsY,         // WG y-dimension  
            workgroupsZ          // WG z-dimension
        );
        
        return fence;
    }
    
    /**
     * Wait for GPU dispatch to complete.
     * 
     * @param fenceHandle GPU fence handle returned from dispatch()
     * @param timeoutMs   Maximum wait time in milliseconds
     * @return true if GPU completed within timeout
     */
    public boolean waitForCompletion(long fenceHandle, long timeoutMs) {
        return nativeWaitForFence(device, fenceHandle, timeoutMs);
    }
    
    // --- Vulkan interop getter methods for BufferManager ---
    
    /**
     * Get the input SSBO binding point.
     */
    public int getInputBindingPoint() {
        return INPUT_BUFFER_BINDING;
    }
    
    /**
     * Get the output SSBO binding point.
     */
    public int getOutputBindingPoint() {
        return OUTPUT_BUFFER_BINDING;
    }
    
    // --- Public getters ---
    
    public boolean isValid() {
        return initialized && computePipeline != 0L;
    }
    
    /**
     * Clean up compute pipeline resources.
     */
    public void shutdown() {
        if (computePipeline != 0L) {
            nativeDestroyPipeline(device, computePipeline);
            computePipeline = 0L;
        }
        if (pipelineLayout != 0L) {
            nativeDestroyPipelineLayout(device, pipelineLayout);
            pipelineLayout = 0L;
        }
        if (shaderModule != 0L) {
            nativeDestroyShaderModule(device, shaderModule);
            shaderModule = 0L;
        }
        if (gpuEvent != 0L) {
            nativeDestroyEvent(device, gpuEvent);
            gpuEvent = 0L;
        }
        initialized = false;
    }
    
    // --- Native Vulkan interop methods ---
    
    /**
     * Create VkShaderModule from SPIR-V binary.
     */
    private native long nativeCreateShaderModule(long device, byte[] spirvCode);
    
    /**
     * Create VkPipelineLayout with descriptor set layouts for SSBO bindings.
     */
    private native long nativeCreatePipelineLayout(long device);
    
    /**
     * Create VkComputePipeline from shader module and pipeline layout.
     */
    private native long nativeCreateComputePipeline(long device, long shaderModule, long layout);
    
    /**
     * Create synchronization event/fence.
     */
    private native long nativeCreateDeviceEvent(long device);
    
    /**
     * Submit compute dispatch command to GPU.
     */
    private native long nativeSubmitComputeDispatch(
        long device, long pipeline, long layout,
        ByteBuffer inputBuffer, long outputSSBO,
        int taskCount, int wgX, int wgY, int wgZ
    );
    
    /**
     * Wait for GPU fence to signal completion.
     */
    private native boolean nativeWaitForFence(long device, long fence, long timeoutMs);
    
    /**
     * Destroy compute pipeline object.
     */
    private native void nativeDestroyPipeline(long device, long pipeline);
    
    /**
     * Destroy pipeline layout object.
     */
    private native void nativeDestroyPipelineLayout(long device, long layout);
    
    /**
     * Destroy shader module object.
     */
    private native void nativeDestroyShaderModule(long device, long module);
    
    /**
     * Destroy GPU event object.
     */
    private native void nativeDestroyEvent(long device, long event);
}