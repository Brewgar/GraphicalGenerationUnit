package com.ggu.gpu;

import com.ggu.config.GGUConfig;
import com.ggu.native_.NativeBridge;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * VulkanContext - Headless Vulkan initialization for GGU.
 * 
 * Manages the Vulkan compute-only pipeline without any window/swapchain dependencies.
 * This is critical for server-side Minecraft where no display surface exists.
 * 
 * Pipeline: VkInstance -> VkSurface (null) -> VkDevice -> VkQueue (compute) -> VkPipeline (compute)
 */
public class VulkanContext {
    
    private static final int WORKGROUP_SIZE = 8; // 8x8x1 workgroups
    
    // Vulkan handle types (Java side stores as long)
    private long instance;          // VkInstance
    private long physicalDevice;    // VkPhysicalDevice
    private long device;            // VkDevice
    private long computeQueue;      // VkQueue
    private int queueFamilyIndex;   // Compute queue family index
    
    // GPU info
    private String deviceName;
    private String driverInfo;
    
    // State
    private boolean initialized = false;
    private final NativeBridge nativeBridge;
    private final GGUConfig config;
    private ComputePipeline computePipeline;
    private BufferManager bufferManager;
    
    public VulkanContext(NativeBridge nativeBridge, GGUConfig config) {
        this.nativeBridge = nativeBridge;
        this.config = config;
        this.deviceName = "Unknown";
        this.driverInfo = "None";
    }
    
    /**
     * Initialize the headless Vulkan pipeline.
     * 
     * Steps:
     * 1. vkCreateInstance (without debug/extension layers for server)
     * 2. vkEnumeratePhysicalDevices - find GPU with compute support
     * 3. vkGetPhysicalDeviceProperties - get device name info
     * 4. vkCreateDevice - create logical device (compute queue only)
     * 5. vkGetDeviceQueue - get compute queue handle
     */
    public boolean initialize() {
        if (nativeBridge == null || !nativeBridge.isValid()) {
            System.err.println("[GGU] VulkanContext: Native bridge not valid");
            return false;
        }
        
        try {
            // Initialize via native bridge (actual Vulkan calls happen in C++)
            long result = nativeInitializeVulkan(config.enableVulkanValidation());
            
            if (result == 0L) {
                System.err.println("[GGU] Vulkan initialization failed - no compatible GPU found");
                return false;
            }
            
            this.device = result;
            this.initialized = true;
            
            // Get GPU info from native layer
            this.deviceName = nativeBridge.nativeGetGPUName();
            if (deviceName.isEmpty()) {
                deviceName = "Vulkan Default";
            }
            this.driverInfo = getDriverInfo();
            
            // Initialize Vulkan subsystems
            computePipeline = new ComputePipeline(this, config);
            if (!computePipeline.initialize()) {
                System.err.println("[GGU] Failed to initialize compute pipeline");
                return false;
            }
            
            bufferManager = new BufferManager(this);
            if (!bufferManager.initialize()) {
                System.err.println("[GGU] Failed to initialize buffer manager");
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            System.err.println("[GGU] Vulkan init exception: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get driver info string from native layer.
     */
    private String getDriverInfo() {
        try {
            return nativeGetDriverInfo();
        } catch (Exception e) {
            return "Unknown";
        }
    }
    
    // --- Native Vulkan interop methods ---
    
    /**
     * Initialize Vulkan through native C++ layer.
     * Returns VkDevice handle or 0 on failure.
     */
    private native long nativeInitializeVulkan(boolean enableValidation);
    
    /**
     * Get Vulkan driver info string.
     */
    private native String nativeGetDriverInfo();
    
    /**
     * Submit a batch of chunk tasks to the compute pipeline.
     */
    public CompletableFuture<java.nio.ByteBuffer> submitBatch(
        java.nio.ByteBuffer taskBuffer, int taskCount
    ) {
        if (!initialized || computePipeline == null || bufferManager == null) {
            throw new IllegalStateException("VulkanContext not initialized");
        }
        
        // Submit to compute pipeline with GPU timestamp event
        long gpuEvent = computePipeline.dispatch(taskBuffer, taskCount);
        
        // Create completion future tied to GPU event
        return bufferManager.readback(gpuEvent, taskCount);
    }
    
    /**
     * Get the Vulkan device handle for native operations.
     */
    public long getDeviceHandle() {
        return device;
    }
    
    /**
     * Get the compute queue family index.
     */
    public int getComputeQueueFamilyIndex() {
        return queueFamilyIndex;
    }
    
    // --- Getters ---
    
    public String deviceName() {
        return deviceName;
    }
    
    public String driverInfo() {
        return driverInfo;
    }
    
    public boolean isValid() {
        return initialized && computePipeline != null && computePipeline.isValid();
    }
    
    public ComputePipeline getComputePipeline() {
        return computePipeline;
    }
    
    public BufferManager getBufferManager() {
        return bufferManager;
    }
    
    public GGUConfig config() {
        return config;
    }
    
    /**
     * Clean up Vulkan resources.
     */
    public void shutdown() {
        if (bufferManager != null) {
            bufferManager.shutdown();
        }
        if (computePipeline != null) {
            computePipeline.shutdown();
        }
        
        // Destroy device (instance is managed by OS)
        if (device != 0L) {
            nativeDestroyDevice(device);
            device = 0L;
        }
        
        this.initialized = false;
    }
    
    private native void nativeDestroyDevice(long device);
}