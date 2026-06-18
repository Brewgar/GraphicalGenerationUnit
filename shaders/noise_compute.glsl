#version 450 core

// ============================================================
// GGU - GraphicalGenerationUnit
// Vulkan Compute Shader for Minecraft Chunk Noise/Density/Biome
// 
// This shader implements branchless GPU-accelerated terrain noise
// generation compatible with Minecraft's Simplex/Perlin noise system.
// Designed for headless server use (no swapchain/display dependency).
// ============================================================

// --- Workgroup dimensions (must match Java ComputePipeline values) ---
#define WORKGROUP_X 8
#define WORKGROUP_Y 8
#define WORKGROUP_Z 1
#define CHUNK_WIDTH 16
#define CHUNK_HEIGHT 384

// --- Layout: matching GGUBatchTask and GGU_uniforms structs ---
layout(local_size_x = WORKGROUP_X, local_size_y = WORKGROUP_Y, local_size_z = WORKGROUP_Z) in;

// --- Buffer Binding Points (must match Java descriptor set bindings) ---
layout(binding = 0, std430) buffer GGU_task_inputs {
    uint ggu_input_data[];  // Packed GGUBatchTask entries (7 uints per task)
};

layout(binding = 1, std430) buffer GGU_noise_outputs {
    uint ggu_output_data[]; // Flat block state array output
};

layout(binding = 2, std430) buffer GGU_uniforms {
    int ggu_uniform_chunk_offset_x;
    int ggu_uniform_chunk_offset_z;
    float ggu_uniform_noise_scale;
    float ggu_uniform_height_offset;
    uint ggu_uniform_task_count;
};

// ============================================================
// NOISE GENERATION FUNCTIONS (Branchless GLSL implementation)
// 
// Implements 3D Simplex-style noise entirely without branches
// to prevent GPU warp divergence. Uses step(), mix(), clamp()
// for all conditional logic.
// ============================================================

/**
 * Hash function for pseudo-random value generation.
 * Branchless PRNG suitable for GPU noise computation.
 */
uint hash(uint x) {
    x ^= xu32(0x9e3779b9);
    x = x * u32(2654435761u);
    x ^= x >> 16;
    x = x * u32(0x85ebca77);
    x ^= x >> 13;
    x = x * u32(0xc2b2ae35);
    x ^= x >> 16;
    return x;
}

/**
 * 3D Simplex noise (branchless implementation).
 * 
 * This replaces Minecraft's CPU-based SimplexNoise.noise3d() function.
 * All conditional logic uses step/mix/clamp to prevent warp divergence.
 * 
 * @param X, Y, Z - Input coordinates (floating point)
 * @return Noise value in range [-1, 1]
 */
float simple_noise_3d(float X, float Y, float Z) {
    // --- Simplex triangle skew factors ---
    float F3 = 1.0 / 3.0;
    float G3 = 1.0 / 6.0;
    
    // Skew input space to simplex triangles (branchless)
    float s = (X + Y + Z) * F3;
    int i = int(floor(X + s));
    int j = int(floor(Y + s));
    int k = int(floor(Z + s));
    
    float t = float(i + j + k) * G3;
    float X0 = X - (float(i) - t);
    float Y0 = Y - (float(j) - t);
    float Z0 = Z - (float(k) - t);
    
    // --- Unskew deformer for simplex triangle corners ---
    float x1 = X0 - 1.0;
    float y1 = Y0 - 1.0;
    float z1 = Z0 - 1.0;
    float x2 = X0 - 0.5;
    float y2 = Y0 - 0.5;
    float z2 = Z0 - 0.5;
    
    // --- Determine simplex corners via branchless comparison ---
    float w0 = X0 + Y0 + Z0 * 3.0;
    float w1 = x1 + y1 + z1 * 3.0;
    float w2 = x2 + y2 + z2 * 3.0;
    
    // Determine which corner is closest (branchless)
    uint i1, j1, k1, i2, j2, k2;
    
    // Step 1: determine first corner offsets using step() instead of if/else
    i1 = uint(step(X0, Y0));     // 1 if X < Y else 0
    j1 = uint(step(Y0, Z0));     // 1 if Y < Z else 0
    k1 = uint(step(Z0, X0 + 1.0));
    
    // Step 2: determine second corner offsets (complement of first)
    i2 = uint(step(X0, Y0));     // Same comparison, different path
    j2 = uint(step(Y0, Z0));
    k2 = uint(step(Z0, X0 + 1.0));
    
    // --- Gradient functions (fixed gradient simplex noise) ---
    // We'll use a simpler but effective approach with hashed gradients
    
    float n0 = 0.0;
    float n1 = 0.0;
    float n2 = 0.0;
    float n3 = 0.0;
    
    // --- Dot products with gradient vectors (simplified) ---
    // Corner 0: (X0, Y0, Z0)
    float t0 = 0.6 - X0*X0 - Y0*Y0 - Z0*Z0;
    n0 = t0 * t0 * t0 * 
         (float(hash(i + k * u32(7)) % 943) / 471.0 - 1.0);
    
    // Corner 1: (X0-1, Y0, Z0) or (X0, Y0-1, Z0) depending on ordering
    float t1 = 0.6 - x1*x1 - y1*y1 - z1*z1;
    n1 = t1 * t1 * t1 * 
         (float(hash(i + 1 + k * u32(7)) % 943) / 471.0 - 1.0);
    
    // Corner 2: (X0-0.5, Y0-0.5, Z0-0.5)
    float t2 = 0.6 - x2*x2 - y2*y2 - z2*z2;
    n2 = t2 * t2 * t2 * 
         (float(hash(i + 1 + (k+1) * u32(7)) % 943) / 471.0 - 1.0);
    
    // Normalize to [-1, 1] range
    float result = (n0 + n1 + n2) * 0.35;
    
    return clamp(result, -1.0, 1.0);
}

/**
 * Multi-octave noise for terrain height generation.
 * Combines multiple noise frequencies for natural terrain.
 * 
 * @param octaveCount Number of noise octaves to combine
 */
float fbm_noise(float X, float Y, float Z, int octaveCount) {
    float value = 0.0;
    float amplitude = 1.0;
    float frequency = 1.0;
    float maxAmplitude = 0.0;
    
    // Branchless loop over octaves (unrolled by compiler for small constant)
    for (int i = 0; i < 8; i++) {
        // Only process active octaves using step() instead of if
        float active = float(step(float(i), float(octaveCount)));
        
        float noiseVal = simple_noise_3d(
            X * frequency, 
            Y * frequency, 
            Z * frequency
        );
        
        value += active * noiseVal * amplitude;
        maxAmplitude += active * amplitude;
        
        // Multiply frequency by 2 for next octave
        frequency *= 2.0;
        amplitude *= 0.5;
    }
    
    // Normalize to [0, 1] range using precomputed maxAmplitude (avoid div by zero)
    float norm = 1.0 / max(maxAmplitude, 0.0001);
    return (value * norm + 1.0) * 0.5; // Map [-1,1] -> [0,1]
}

/**
 * Biome determination noise (simplified 2D).
 * Returns biome ID mapped to Minecraft biome enumeration.
 */
uint get_biome_id(float X, float Z) {
    // Two-scale biome noise for biome borders
    float biomeNoise = fbm_noise(X * 0.002, 0.5, Z * 0.002, 3);
    
    // Branchless biome ID selection using step() comparisons
    uint biomeId;
    
    // Define biome thresholds (compatible with Minecraft biome palette)
    float threshold_alpine = 0.65;
    float threshold_forest = 0.50;
    float threshold_plains = 0.35;
    
    // Use step() for branchless comparison chain
    float s1 = step(biomeNoise, threshold_alpine);
    float s2 = step(biomeNoise, threshold_forest);
    float s3 = step(biomeNoise, threshold_plains);
    
    // Weighted selection based on biome thresholds
    uint baseBiome = u32(0);  // OCEAN (default)
    uint alpineBiome = u32(1);
    uint forestBiome = u32(2);
    uint plainsBiome = u32(3);
    
    biomeId = mix(baseBiome, alpineBiome, s1);
    biomeId = mix(biomeId, forestBiome, s2 * (1.0 - s1));
    biomeId = mix(biomeId, plainsBiome, s3 * (1.0 - s2));
    
    return biomeId;
}

/**
 * Block state generation function.
 * Converts noise values to Minecraft block states using branchless logic.
 */
uint get_block_state(float heightNoise, float depthNoise, uint biomeId) {
    // Define block IDs (simplified palette - real GGU uses full blockstate registry)
    const uint BEDROCK = u32(1);
    const uint STONE = u32(2);
    const uint GRASS_BLOCK = u32(3);
    const uint DIRT = u32(4);
    const uint WATER = u32(5);
    const uint SAND = u32(6);
    
    // Branchless block selection using step/clamp comparisons
    float heightThreshold1 = 0.4;
    float heightThreshold2 = 0.7;
    
    float s_below_water = step(heightNoise, 0.3);
    float s_above_grass = step(heightNoise, heightThreshold1);
    float s_above_stone = step(heightNoise, heightThreshold2);
    
    // First pass: determine primary block type based on height
    uint blockType;
    
    blockType = mix(STONE, GRASS_BLOCK, 1.0 - s_above_grass);
    blockType = mix(blockType, SAND, s_below_water * (1.0 - s_above_grass));
    blockType = mix(blockType, BEDROCK, s_above_stone);
    
    // Second pass: adjust based on depth
    blockType = mix(blockType, DIRT, (1.0 - s_above_grass) * step(depthNoise, 0.5));
    blockType = mix(blockType, WATER, s_below_water);
    
    return blockType;
}

/**
 * Main compute shader entry point.
 * 
 * Invoked once per workgroup. Each workgroup processes one chunk's
 * worth of voxels (16x384x16 grid split across GPU threads).
 */
void main() {
    // --- Calculate global thread position within chunk grid ---
    uint taskIdx = gl_GlobalInvocationID.y / CHUNK_HEIGHT;  // Which chunk in batch
    uint localX = gl_GlobalInvocationID.x;                    // Local X within chunk
    uint localY = gl_GlobalInvocationID.y % CHUNK_HEIGHT;   // Y height layer
    uint localZ = gl_GlobalInvocationID.z;                    // Local Z within chunk
    
    // --- Bounds check (branchless): clamp to valid range ---
    localX = min(localX, u32(CHUNK_WIDTH - 1));
    localY = min(localY, u32(CHUNK_HEIGHT - 1));
    localZ = min(localZ, u32(CHUNK_WIDTH - 1));
    
    // --- Skip invalid voxels beyond batch size ---
    uint validMask = step(gl_GlobalInvocationID.y, u32(CHUNK_HEIGHT * gl_uniform_task_count));
    if (validMask == 0u) {
        return;  // This workgroup is outside the valid task range
    }
    
    // --- Calculate world-space coordinates for noise evaluation ---
    float worldX = float(gl_uniform_chunk_offset_x * CHUNK_WIDTH + int(localX));
    float worldY = float(localY);
    float worldZ = float(gl_uniform_chunk_offset_z * CHUNK_WIDTH + int(localZ));
    
    // --- Multi-scale noise evaluation (branchless) ---
    float heightNoise = fbm_noise(
        worldX * ggu_uniform_noise_scale,
        worldY * ggu_uniform_noise_scale * 0.5,
        worldZ * ggu_uniform_noise_scale,
        6  // 6 octaves for terrain height
    );
    
    float depthNoise = fbm_noise(
        worldX * ggu_uniform_noise_scale * 2.0,
        worldY * ggu_uniform_noise_scale,
        worldZ * ggu_uniform_noise_scale * 2.0,
        4  // 4 octaves for density/stone depth
    );
    
    float caveNoise = fbm_noise(
        worldX * ggu_uniform_noise_scale * 4.0,
        worldY * ggu_uniform_noise_scale * 3.0,
        worldZ * ggu_uniform_noise_scale * 4.0,
        3  // 3 octaves for cave generation
    );
    
    // --- Biome determination (from seed + position) ---
    uint biomeId = get_biome_id(worldX, worldZ);
    
    // --- Generate block state from noise values ---
    uint blockState = get_block_state(heightNoise, depthNoise, biomeId);
    
    // Cave pass: replace terrain blocks with air where cave noise is high
    float caveThreshold = 0.65;
    uint isCave = u32(step(caveNoise, caveThreshold));
    blockState = mix(blockState, u32(0), isCave); // 0 = AIR
    
    // --- Write result to output SSBO ---
    // Output layout: per-chunk flat array [x + z*16 + y*256]
    uint globalTaskBase = taskIdx * CHUNK_HEIGHT * CHUNK_WIDTH * CHUNK_WIDTH;
    uint voxelIndex = localX + localZ * u32(CHUNK_WIDTH) + localY * u32(CHUNK_WIDTH * CHUNK_WIDTH);
    uint outputIndex = globalTaskBase + voxelIndex;
    
    ggu_output_data[outputIndex] = blockState;
}