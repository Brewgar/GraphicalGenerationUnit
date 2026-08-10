# GraphicalGenerationUnit (GGU) - GPU-Accelerated Minecraft Chunk Generation

## Overview
GGU is a high-performance Fabric mod that offloads chunk terrain generation (Noise/Density/Biome) to the GPU using Vulkan Compute Shaders. It serves as a seamless replacement for CPU-heavy generation while maintaining compatibility with the 'Chunky' pre-generator.

## Architecture
- **Phase 1**: GGU Pipeline - Intercepts `ChunkStatus.NOISE`, queues tasks, dispatches to GPU
- **Phase 2**: Fabric Mixins - Batching mechanism for efficient GPU utilization
- **Phase 3**: Native Bridge - C++/JNI/Vulkan compute execution
- **Phase 4**: Vulkan Compute Shader - Branchless noise evaluation
- **Phase 5**: Asynchronous Assembly - CompletableFuture-based chunk completion

## Features
-  Zero-Copy memory bridge via DirectByteBuffer
-  Branchless GPU noise computation (no warp divergence)
-  Headless Vulkan initialization (no swapchain dependency)
-  Chunky pre-generator compatible
-  Non-blocking async pipeline

## Project Structure
```
GraphicalGenerationUnit/
├── src/
│   └── main/
│       ├── java/com/ggu/
│       │   ├── GGU.java                    [Mod entry point]
│       │   ├── gpu/
│       │   │   ├── VulkanContext.java      [Headless Vulkan init]
│       │   │   ├── ComputePipeline.java    [Compute pipeline setup]
│       │   │   └── BufferManager.java      [SSBO/DMA management]
│       │   ├── generation/
│       │   │   ├── GGUNoiseGenerator.java  [GPU noise offload]
│       │   │   └── GGUChunkGenerator.java  [Chunky-compatible generator]
│       │   ├── batching/
│       │   │   └── GGU_BatchDispatcher.java [Task batching]
│       │   ├── async/
│       │   │   └── AsyncChunkAssembler.java [CompletableFuture assembly]
│       │   └── native_/
│       │       └── NativeBridge.java       [JNI/Panama bindings]
│       └── resources/
│           ├── fabric.mod.json              [Mod manifest]
│           └── ggu.mixins.json             [Mixin config]
├── native/
│   └── src/
│       ├── ggu_native.cpp       [JNI entry points]
│       ├── vulkan_compute.cpp  [Vulkan compute dispatch]
│       └── memory_manager.cpp  [SSBO memory management]
└── shaders/
    └── noise_compute.glsl      [GLSL compute shader]
```

## Build Requirements
- Java 21+ (Minecraft 1.21+)
- Vulkan 1.3 SDK
- C++17 compiler
- Fabric Loader
- Gradle 8+

## Performance Benchmarks
- Batch size: 64 chunks per GPU dispatch
- Memory overhead: ~2MB per batch
- Latency: <5ms GPU-CPU sync
