# GraphicalGenerationUnit (GGU)

A Minecraft mod that makes chunk generation go brrr by handing the work to your GPU.

It uses Vulkan compute shaders to generate terrain, as a drop-in replacement for the usual CPU-heavy approach. Your graphics card has been sitting around doing nothing while your processor does all the work. GGU gives it something to do. It works with the Chunky pre-generator.

## Features

- Zero-copy memory bridge via `DirectByteBuffer`, so data isn't shuffled around more than it needs to be
- Branchless GPU noise computation (no warp divergence)
- Headless Vulkan initialization, no swapchain needed
- Chunky pre-generator compatible
- Non-blocking async pipeline

## What you need to build it

- Java 21+ (Minecraft 1.21+)
- Vulkan 1.3 SDK
- A C++17 compiler
- Fabric Loader
- Gradle 8+

Build steps are in [BUILD.md](BUILD.md).

## Numbers

- Batch size: 64 chunks per GPU dispatch
- Memory overhead: about 2 MB per batch
- GPU-to-CPU sync latency: under 5 ms
