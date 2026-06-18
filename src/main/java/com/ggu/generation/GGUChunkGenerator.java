package com.ggu.generation;

import com.ggu.GGU;
import com.ggu.batching.GGU_BatchDispatcher;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * GGUChunkGenerator - Chunky-compatible chunk generator provider.
 * 
 * Registers itself as an alternative generation pathway for Minecraft's
 * ChunkManager when GGU GPU acceleration is active. Maintains full
 * compatibility with Chunky's expected CompletableFuture chain.
 */
public class GGUChunkGenerator {
    
    private static volatile boolean accelerationActive = false;
    private static final ThreadLocal<GGUBatchDispatcher> threadLocalDispatcher = new ThreadLocal<>();
    
    // Minecraft chunk generator types for registry
    private static final int CHUNK_TYPE_PROTO = 0;
    private static final int CHUNK_TYPE_REGULAR = 1;
    
    /**
     * Register GGU as a chunk generator provider with the Chunky integration point.
     * Called during mod initialization from GGU.onInitialize().
     */
    public static void registerProvider() {
        accelerationActive = true;
        System.out.println("[GGU] Registered GPU-accelerated chunk generation provider");
    }
    
    /**
     * Disable GPU acceleration and fall back to CPU generation.
     * Called when GPU initialization fails or is disabled via config.
     */
    public static void disableAcceleration() {
        accelerationActive = false;
        System.out.println("[GGU] GPU acceleration disabled, falling back to CPU");
    }
    
    /**
     * Check if GGU GPU acceleration is currently active.
     */
    public static boolean isActive() {
        return accelerationActive && GGUBatchDispatcher.isAvailable();
    }
    
    /**
     * Get the GGU batch dispatcher for the current thread.
     * Each server thread gets its own dispatcher instance for thread safety.
     */
    public static GGUBatchDispatcher getDispatcher() {
        GGUBatchDispatcher dispatcher = threadLocalDispatcher.get();
        if (dispatcher == null || !dispatcher.isRunning()) {
            // Create new dispatcher for this thread
            if (GGU.isAccelerationActive()) {
                dispatcher = new GGUBatchDispatcher(
                    GGU.getNativeBridge(),
                    GGU.getVulkanContext()
                );
                dispatcher.start();
                threadLocalDispatcher.set(dispatcher);
            }
        }
        return dispatcher;
    }
    
    /**
     * Generate a chunk's noise data asynchronously via GPU.
     * 
     * This is the primary integration method called by Chunky through
     * the modified NoiseChunkGenerator mixin. It returns immediately
     * with a CompletableFuture that will complete when GPU processing finishes.
     * 
     * @param chunkX       World X coordinate of the chunk
     * @param chunkZ       World Z coordinate of the chunk
     * @param worldSeed    Minecraft world seed
     * @param biomeSource  Biome source data (passed through to GPU)
     * @return CompletableFuture that completes with a ProtoChunk containing generated blocks
     */
    public static CompletableFuture<net.minecraft.world.chunk.ProtoChunk> generateChunk(
        int chunkX, int chunkZ, long worldSeed, 
        net.minecraft.world.biome.source.BiomeSource biomeSource
    ) {
        if (!accelerationActive) {
            // Fallback to vanilla CPU generation
            return CompletableFuture.supplyAsync(() -> {
                return generateChunkCPU(chunkX, chunkZ, worldSeed);
            });
        }
        
        GGUBatchDispatcher dispatcher = getDispatcher();
        
        // Submit noise generation task to GPU via batch dispatcher
        CompletableFuture<ByteBuffer> gpuResultFuture = 
            dispatcher.submitNoiseTask(chunkX, chunkZ, worldSeed, null);
        
        // Chain the async completion: GPU results -> ProtoChunk assembly
        return gpuResultFuture.thenApplyAsync(resultsBuffer -> {
            return assembleProtoChunkFromGPUResults(
                chunkX, chunkZ, worldSeed, resultsBuffer
            );
        });
    }
    
    /**
     * Assemble a ProtoChunk from GPU-processed block state data.
     * 
     * This runs AFTER GPU computation completes and receives the
     * flat block state array output from the Vulkan compute shader.
     * 
     * Output buffer layout from GGU shader:
     * [0:256*1024] chunk0_blockStates (16*384*16 u32 = 3.9MB)
     * [256*1024:512*1024] chunk1_blockStates (if batched)
     * etc.
     */
    private net.minecraft.world.chunk.ProtoChunk assembleProtoChunkFromGPUResults(
        int chunkX, int chunkZ, long seed, ByteBuffer gpuBlockStates
    ) {
        // Create new ProtoChunk for injection
        net.minecraft.util.math.ChunkPos chunkPos = 
            new net.minecraft.util.math.ChunkPos(chunkX, chunkZ);
        
        net.minecraft.world.biome.source.BiomeSource biomeSource = 
            net.minecraft.world.biome.source.BiomeSources.flat(); // TODO: Get actual biome source
        
        net.minecraft.world.chunk.ProtoChunk protoChunk = new net.minecraft.world.chunk.ProtoChunk(
            chunkPos, seed, System.currentTimeMillis(), biomeSource
        );
        
        if (gpuBlockStates != null && gpuBlockStates.hasRemaining()) {
            injectBlockStatesIntoProtoChunk(protoChunk, gpuBlockStates);
        }
        
        return protoChunk;
    }
    
    /**
     * Inject GPU-generated block states into a ProtoChunk.
     * 
     * Maps the flat output array from the Vulkan shader to Minecraft's
     * internal block state storage structure (Regionfile format compatible).
     */
    private void injectBlockStatesIntoProtoChunk(
        net.minecraft.world.chunk.ProtoChunk chunk, ByteBuffer gpuData
    ) {
        int x0 = chunk.getPos().getStartX();
        int z0 = chunk.getPos().getStartZ();
        
        // Iterate over all voxel positions and set block states
        for (int y = 0; y < 384; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    // Calculate flat array index (row-major: x + z*W + y*W*H)
                    int index = x + z * 16 + y * (16 * 384);
                    
                    // Read u32 block state ID from GPU output
                    if (index * 4 < gpuData.remaining()) {
                        int blockStateId = gpuData.getInt(index * 4);
                        
                        // Convert block state ID to Minecraft BlockState
                        net.minecraft.block.BlockState blockState = 
                            getBlockStateFromID(blockStateId);
                        
                        // Set the block state in the ProtoChunk
                        chunk.setBlockState(
                            new net.minecraft.util.math.BlockPos(x0 + x, y, z0 + z),
                            blockState,
                            true // skipNeighbors (optimization for generation)
                        );
                    }
                }
            }
        }
    }
    
    /**
     * Convert GPU-generated block state ID to Minecraft BlockState.
     * 
     * In a real GGU implementation, this would use the full Minecraft
     * blockstate registry to map IDs to actual block states.
     * Simplified here with a placeholder mapping.
     */
    private net.minecraft.block.BlockState getBlockStateFromID(int blockStateId) {
        // TODO: Full registry lookup (needs Minecraft 1.21+ blockstate palette)
        switch (blockStateId) {
            case 0: return net.minecraft.block.Blocks.AIR.getDefaultState();
            case 1: return net.minecraft.block.Blocks.BEDROCK.getDefaultState();
            case 2: return net.minecraft.block.Blocks.STONE.getDefaultState();
            case 3: return net.minecraft.block.Blocks.GRASS_BLOCK.getDefaultState();
            case 4: return net.minecraft.block.Blocks.DIRT.getDefaultState();
            case 5: return net.minecraft.block.Blocks.WATER.getDefaultState();
            case 6: return net.minecraft.block.Blocks.SAND.getDefaultState();
            default: return net.minecraft.block.Blocks.AIR.getDefaultState();
        }
    }
    
    /**
     * Fallback CPU chunk generation (vanilla Simplex noise).
     * Called when GPU acceleration is disabled or unavailable.
     */
    private net.minecraft.world.chunk.ProtoChunk generateChunkCPU(
        int chunkX, int chunkZ, long worldSeed
    ) {
        System.out.println("[GGU] Falling back to CPU generation for chunk (" + 
            chunkX + "," + chunkZ + ")");
        
        // TODO: Use Minecraft's vanilla SimplexNoise generator here
        // For now, return an empty ProtoChunk as placeholder
        
        net.minecraft.util.math.ChunkPos chunkPos = 
            new net.minecraft.util.math.ChunkPos(chunkX, chunkZ);
        
        return new net.minecraft.world.chunk.ProtoChunk(
            chunkPos, worldSeed, System.currentTimeMillis(), null
        );
    }
    
    /**
     * Shutdown GGU chunk generator and clean up dispatcher threads.
     */
    public static void shutdown() {
        GGUBatchDispatcher dispatcher = threadLocalDispatcher.get();
        if (dispatcher != null) {
            dispatcher.stop();
            threadLocalDispatcher.remove();
        }
        accelerationActive = false;
    }
}