package com.ggu.config;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * GGU Configuration System.
 * 
 * Handles all runtime configuration for the GraphicalGenerationUnit mod,
 * including GPU settings, batching parameters, and Chunky integration options.
 */
public record GGUConfig(
    boolean enabled,
    boolean fallback,
    int batchSize,
    int gpuQueueDepth,
    boolean enableVulkanValidation,
    String nativeLibraryPath,
    int maxBatchWaitMs,
    boolean enablePerfMetrics,
    double noisePrecision
    
) {
    private static final Path CONFIG_PATH = Paths.get("config/ggu/ggu-config.properties");
    private static final int DEFAULT_BATCH_SIZE = 64;
    private static final int DEFAULT_GPU_QUEUE_DEPTH = 3;
    private static final int DEFAULT_MAX_WAIT_MS = 50;
    
    /**
     * Load configuration from file or use defaults.
     */
    public static GGUConfig load() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                Properties props = new Properties();
                try (FileInputStream fis = Files.newInputStream(CONFIG_PATH)) {
                    props.load(fis);
                }
                return fromProperties(props);
            }
        } catch (IOException e) {
            System.err.println("[GGU] Failed to load config file, using defaults: " + e.getMessage());
        }
        
        // Return default configuration
        return Defaults.get();
    }
    
    /**
     * Save configuration to disk.
     */
    public void save() {
        try {
            Path parent = CONFIG_PATH.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            
            Properties props = toProperties();
            try (FileOutputStream fos = Files.newOutputStream(CONFIG_PATH)) {
                props.store(fos, "GGU Configuration - Do not edit manually");
            }
        } catch (IOException e) {
            System.err.println("[GGU] Failed to save config: " + e.getMessage());
        }
    }
    
    /**
     * Create configuration with GPU fallback enabled.
     */
    public static GGUConfig withFallback(boolean fallback) {
        return new GGUConfig(
            !fallback,      // enabled
            fallback,       // fallback
            DEFAULT_BATCH_SIZE,
            DEFAULT_GPU_QUEUE_DEPTH,
            false,          // validation (disabled in fallback)
            "ggu/native/lib",
            DEFAULT_MAX_WAIT_MS,
            false,          // metrics (disabled in fallback)
            1.0             // full precision fallback
        );
    }
    
    /**
     * Convert config to Properties for serialization.
     */
    public Properties toProperties() {
        Properties props = new Properties();
        props.setProperty("ggu.enabled", String.valueOf(enabled));
        props.setProperty("ggu.fallback", String.valueOf(fallback));
        props.setProperty("ggu.batch.size", String.valueOf(batchSize));
        props.setProperty("gpu.queue.depth", String.valueOf(gpuQueueDepth));
        props.setProperty("vulkan.validation", String.valueOf(enableVulkanValidation));
        props.setProperty("native.library.path", nativeLibraryPath);
        props.setProperty("ggu.batch.maxWaitMs", String.valueOf(maxBatchWaitMs));
        props.setProperty("perf.metrics.enabled", String.valueOf(enablePerfMetrics));
        props.setProperty("noise.precision", Double.toString(noisePrecision));
        return props;
    }
    
    /**
     * Load configuration from Properties.
     */
    private static GGUConfig fromProperties(Properties props) {
        return new GGUConfig(
            Boolean.parseBoolean(props.getProperty("ggu.enabled", "true")),
            Boolean.parseBoolean(props.getProperty("ggu.fallback", "false")),
            Integer.parseInt(props.getProperty("ggu.batch.size", String.valueOf(DEFAULT_BATCH_SIZE))),
            Integer.parseInt(props.getProperty("gpu.queue.depth", String.valueOf(DEFAULT_GPU_QUEUE_DEPTH))),
            Boolean.parseBoolean(props.getProperty("vulkan.validation", "false")),
            props.getProperty("native.library.path", "ggu/native/lib"),
            Integer.parseInt(props.getProperty("ggu.batch.maxWaitMs", String.valueOf(DEFAULT_MAX_WAIT_MS))),
            Boolean.parseBoolean(props.getProperty("perf.metrics.enabled", "false")),
            Double.parseDouble(props.getProperty("noise.precision", "1.0"))
        );
    }
    
    /**
     * Default configuration values.
     */
    private static final GGUConfig Defaults = new GGUConfig(
        true,                           // enabled
        false,                          // fallback
        DEFAULT_BATCH_SIZE,             // batchSize
        DEFAULT_GPU_QUEUE_DEPTH,       // gpuQueueDepth
        false,                          // enableVulkanValidation
        "ggu/native/lib",              // nativeLibraryPath
        DEFAULT_MAX_WAIT_MS,           // maxBatchWaitMs
        true,                          // enablePerfMetrics
        1.0                            // noisePrecision
    );
}