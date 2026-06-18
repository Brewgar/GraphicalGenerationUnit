package com.ggu;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ggu.batching.GGU_BatchDispatcher;
import com.ggu.gpu.VulkanContext;
import com.ggu.generation.GGUChunkGenerator;
import com.ggu.config.GGUConfig;
import com.ggu.native_.NativeBridge;

/**
 * GraphicalGenerationUnit (GGU) - Main Mod Entry Point.
 * 
 * GGU intercepts Minecraft's chunk generation pipeline and offloads noise/density/biome
 * computation to the GPU via Vulkan compute shaders. This is the master initialization
 * class that sets up all subsystems.
 * 
 * Pipeline Overview:
 * 1. Chunky Request -> ServerChunkManager -> GGU Mixin intercept
 * 2. GGU Mixin -> GGU Task Queue (Batching mechanism)
 * 3. GGU Task Queue -> JNI/Panama -> Vulkan Compute (Processing)
 * 4. Vulkan SSBO -> DMA Transfer -> DirectByteBuffer
 * 5. Java-side -> Assembly of ProtoChunk -> future.complete(chunk)
 */
public class GGU implements ModInitializer {
    
    public static final String MOD_ID = "ggu";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    // Global configuration
    private static GGUConfig config;
    private static VulkanContext vulkanContext;
    private static GGU_BatchDispatcher batchDispatcher;
    private static NativeBridge nativeBridge;
    
    @Override
    public void onInitialize() {
        LOGGER.info("=== GraphicalGenerationUnit (GGU) Initializing ===");
        
        // Load configuration
        config = GGUConfig.load();
        LOGGER.info("Configuration loaded: batch={}, gpuQueue={}, validation={}",
            config.batchSize(), config.gpuQueueDepth(), config.enableVulkanValidation());
        
        // Initialize native bridge first (required by Vulkan)
        try {
            nativeBridge = NativeBridge.getInstance();
            nativeBridge.initialize();
            LOGGER.info("Native bridge initialized successfully");
        } catch (NativeBridge.NativeInitException e) {
            LOGGER.warn("Native bridge failed, falling back to CPU: {}", e.getMessage());
            fallbackToCPU();
            return;
        }
        
        // Initialize Vulkan compute context (headless - no swapchain)
        try {
            vulkanContext = new VulkanContext(nativeBridge, config);
            if (!vulkanContext.initialize()) {
                LOGGER.warn("Vulkan initialization failed, falling back to CPU");
                fallbackToCPU();
                return;
            }
            LOGGER.info("Vulkan context initialized: {} [{}]", 
                vulkanContext.deviceName(), vulkanContext.driverInfo());
        } catch (Exception e) {
            LOGGER.warn("Vulkan init error, falling back to CPU: {}", e.getMessage());
            fallbackToCPU();
            return;
        }
        
        // Initialize batch dispatcher for chunk generation tasks
        batchDispatcher = new GGU_BatchDispatcher(nativeBridge, vulkanContext);
        batchDispatcher.start();
        LOGGER.info("Batch dispatcher started (batchSize={})", config.batchSize());
        
        // Register GGU as alternative chunk generator provider
        GGUChunkGenerator.registerProvider();
        LOGGER.info("GGU chunk generator provider registered");
        
        LOGGER.info("=== GraphicalGenerationUnit (GGU) Ready ===");
        LOGGER.info("GPU acceleration active. Chunky integration enabled.");
    }
    
    /**
     * Fallback to vanilla CPU generation when GPU unavailable.
     */
    private void fallbackToCPU() {
        config = GGUConfig.withFallback(true);
        GGUChunkGenerator.disableAcceleration();
        LOGGER.warn("GGU operating in fallback mode - all generation delegated to CPU");
    }
    
    /**
     * Get the global GGU configuration.
     */
    public static GGUConfig getConfig() {
        return config;
    }
    
    /**
     * Get the Vulkan compute context.
     */
    public static VulkanContext getVulkanContext() {
        return vulkanContext;
    }
    
    /**
     * Get the batch dispatcher for submitting chunk generation tasks.
     */
    public static GGU_BatchDispatcher getBatchDispatcher() {
        return batchDispatcher;
    }
    
    /**
     * Get the native bridge for JNI/Panama communication with Vulkan.
     */
    public static NativeBridge getNativeBridge() {
        return nativeBridge;
    }
    
    /**
     * Check if GPU acceleration is active.
     */
    public static boolean isAccelerationActive() {
        return vulkanContext != null && vulkanContext.isValid();
    }
}