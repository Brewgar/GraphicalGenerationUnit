# GraphicalGenerationUnit (GGU) - GPU-Accelerated Minecraft Chunk Generation

## Overview
GGU is a mod which makes chunk generation go brrr via vulkan compute shaders. It serves as a seamless replacement for CPU heavy generation. Chunky compatible.

## Features
-  Zero-Copy memory bridge via DirectByteBuffer
-  Branchless GPU noise computation (no warp divergence)
-  Headless Vulkan initialization (no swapchain dependency)
-  Chunky pre-generator compatible
-  Non-blocking async pipeline

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
