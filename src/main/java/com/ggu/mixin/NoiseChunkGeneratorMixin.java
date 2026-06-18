package com.ggu.mixin;

import com.ggu.GGU;
import com.ggu.batching.GGU_BatchDispatcher;
import com.mojang.serialization.DynamicOps;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.NoiseChunkGenerator;
import net.minecraft.world.gen.ChunkContext;
import net.minecraft.world.chunk.ChunkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * NoiseChunkGeneratorMixin - GGU Mixin that intercepts Minecraft's noise generation pipeline.
 * 
 * This mixin intercepts the critical path where Chunky requests chunk noise/density/biome
 * data and redirects it to the GPU compute pipeline instead of CPU noise evaluation.
 * 
 * Interception Strategy:
 * 1. @ModifyArg on generateNoise() -> redirect to GPU dispatch
 * 2. @Inject on getOrCreateChunk() -> inject CompletableFuture chain
 * 3. The server thread NEVER blocks - all GPU waits are async
 */
@Mixin(NoiseChunkGenerator.class)
public class NoiseChunkGeneratorMixin {
    
    /**
     * Intercept chunk noise generation and redirect to GPU via GGU batch dispatcher.
     * 
     * Minecraft's normal flow:
     *   ChunkStatus.NOISE -> generateNoise() -> CPU Simplex/Perlin noise -> ProtoChunk
     * 
     * GGU modified flow:
     *   ChunkStatus.NOISE -> GGU submitTask() -> GPU Vulkan compute -> ProtoChunk
     */
    @Inject(
        method = "Lnet/minecraft/world/gen/NoiseChunkGenerator;generateChunkState*",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void interceptGenerateChunkState(
        ChunkContext context,
        net.minecraft.world.chunk.ChunkGenerator chunkGenerator,
        net.minecraft.world.chunk.UpgradeableChunkStorage chunkStorage,
        net.minecraft.util.math.ChunkPos chunkPos,
        CallbackInfoReturnable<CompletableFuture<net.minecraft.world.chunk.ChunkAccess>> cir
    ) {
        // Check if GGU GPU acceleration is active
        if (!GGU.isAccelerationActive()) {
            return; // Let vanilla path continue
        }
        
        GGUBatchDispatcher dispatcher = GGUBatchDispatcher.getInstance();
        long worldSeed = context.seed();
        int chunkX = chunkPos.x;
        int chunkZ = chunkPos.z;
        
        // Create CompletableFuture that will be completed when GPU finishes
        CompletableFuture<net.minecraft.world.chunk.ChunkAccess> gguFuture = 
            submitChunkTaskToGPU(dispatcher, chunkX, chunkZ, worldSeed);
        
        cir.setReturnValue(gguFuture);
    }
    
    /**
     * Submit a chunk generation task to GGU and return CompletableFuture.
     */
    private CompletableFuture<net.minecraft.world.chunk.ChunkAccess> submitChunkTaskToGPU(
        GGUBatchDispatcher dispatcher, int chunkX, int chunkZ, long worldSeed
    ) {
        // Submit noise data to GPU batch dispatcher
        CompletableFuture<ByteBuffer> gpuResultFuture = 
            dispatcher.submitNoiseTask(chunkX, chunkZ, worldSeed, null);
        
        // Chain async assembly: when GPU results arrive, assemble the ProtoChunk
        return gpuResultFuture.thenComposeAsync(resultsBuffer -> {
            return assembleProtoChunkFromGPUResults(chunkX, chunkZ, worldSeed, resultsBuffer);
        }, net.minecraft.util.thread.ThreadExecutor.getServerMain()); // Main server thread (Minecraft fast)
    }
    
    /**
     * Assemble a ProtoChunk from GPU-processed noise data.
     * 
     * This runs on the main server thread AFTER GPU computation completes.
     * It receives the flat block state array and constructs a valid Minecraft chunk object.
     */
    private CompletableFuture<net.minecraft.world.chunk.ChunkAccess> assembleProtoChunkFromGPUResults(
        int chunkX, int chunkZ, long seed, ByteBuffer gpuBlockStates
    ) {
        // Create ProtoChunk from GPU data
        ProtoChunk protoChunk = new ProtoChunk(
            new net.minecraft.util.math.ChunkPos(chunkX, chunkZ),
            seed,
            System.currentTimeMillis(),
            null // biome source (populated separately by Chunky)
        );
        
        // Inject block states from GPU results into ProtoChunk
        injectBlockStates(protoChunk, gpuBlockStates);
        
        // Complete the future with the assembled chunk
        return CompletableFuture.completedFuture(protoChunk);
    }
    
    /**
     * Inject GPU-generated block states into a ProtoChunk.
     * 
     * Output buffer layout from GGU shader:
     * Each voxel [x,y,z] maps to index = x + z*16 + y*(16*16) in the flat array
     * Each entry is u32 representing a block state ID
     */
    private void injectBlockStates(ProtoChunk chunk, ByteBuffer gpuData) {
        if (gpuData == null || !gpuData.hasRemaining()) {
            return; // No data to inject
        }
        
        int x0 = chunk.getPos().getStartX();
        int z0 = chunk.getPos().getStartZ();
        
        // Iterate over all block positions and set block states
        for (int y = 0; y < 384; y++) { // Full height
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int index = x + z * 16 + y * 256; // Row-major ordering
                    int blockStateId = gpuData.getInt(index * 4); // u32 per entry
                    
                    chunk.setBlockState(
                        new net.minecraft.util.math.BlockPos(x0 + x, y, z0 + z),
                        net.minecraft.block.Blocks.AIR.getDefaultState() // Placeholder: replace with actual block from ID
                    );
                }
            }
        }
    }
    
    /**
     * Intercept the noise generation step and redirect to GGU GPU path.
     * 
     * @Redirect targets the specific method call in NoiseChunkGenerator.generateNoise().
     * When GGU is active, redirects to a GPU dispatch stub that returns immediately.
     */
    @Redirect(
        method = "Lnet/minecraft/world/gen/NoiseChunkGenerator;generateNoise*",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/gen/NoiseChunkGenerator;generateNoise*"),
        remap = false
    )
    private Object redirectNoiseGeneration(NoiseChunkGenerator instance, Object... args) {
        if (GGU.isAccelerationActive()) {
            // Return a stub/future that will be completed when GPU finishes
            return CompletableFuture.completedFuture(null);
        }
        // Vanilla path (shouldn't reach here if injection worked)
        return null;
    }
}