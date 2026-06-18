// ============================================================
// GGU - GraphicalGenerationUnit
// Native Bridge Implementation (C++/Vulkan)
// 
// This file implements the native C++ layer that bridges Java
// to Vulkan compute APIs. It handles:
// - Vulkan instance/device initialization (headless mode)
// - Compute shader module creation from SPIR-V
// - Pipeline layout, descriptor set, and command buffer management
// - SSBO memory allocation/deallocation
// - Zero-copy DMA transfers for GPU->CPU readback
// ============================================================

#include <vulkan/vulkan.h>
#include <jni.h>
#include <cstdio>
#include <cstring>
#include <vector>
#include <memory>
#include <string>
#include <algorithm>

// ============================================================
// GGU Native Context Structure
// 
// Contains all Vulkan state for the GGU compute pipeline.
// Allocated on first nativeGGUInit() call, freed on shutdown.
// ============================================================

struct GGUNativeContext {
    // Vulkan core objects
    VkInstance instance;
    VkPhysicalDevice physicalDevice;
    VkDevice device;
    VkQueue computeQueue;
    uint32_t computeQueueFamilyIndex;
    
    // Compute pipeline objects
    VkShaderModule computeShaderModule;
    VkPipelineLayout pipelineLayout;
    VkPipeline computePipeline;
    
    // Descriptor sets (SSBO bindings)
    VkDescriptorSetLayout descriptorSetLayout;
    VkDescriptorPool descriptorPool;
    VkDescriptorSet descriptorSet;
    
    // Buffers (SSBO + UBO)
    VkBuffer inputBuffer;
    VkDeviceMemory inputBufferMemory;
    VkBuffer outputBuffers[4];   // Ping-pong pool (max queue depth)
    VkDeviceMemory outputBufferMemories[4];
    VkBuffer uniformBuffer;
    VkDeviceMemory uniformBufferMemory;
    
    // Synchronization
    VkFence renderFence;
    VkCommandPool commandPool;
    
    // Memory management
    void* inputHostPointer;    // Mapped host pointer for input SSBO
    void* outputHostPointers[4]; // Mapped host pointers for output SSBOs
    
    // Configuration
    uint32_t batchSize;
    bool initialized;
};

// Global GGU context instance (singleton)
static GGUNativeContext g_gguContext = {};

// ============================================================
// JNI Method Bindings
// 
// These are the native methods declared in Java classes.
// Each is registered via JNI_OnLoad() for dynamic linking.
// ============================================================

// --- Java method signatures (matching NativeBridge.java) ---
// long nativeGGUInit() -> creates GGU context and initializes Vulkan
// long nativeInitializeVulkan(boolean enableValidation)
// long nativeDispatchBatch(long ctx, ByteBuffer inputBuffer, int taskCount)
// boolean nativeWaitForCompletion(long ctx, long timeoutMs)
// ByteBuffer nativeReadbackSSBO(long ctx, int byteCount)
// void nativeFree(long pointer)
// String nativeGetGPUName()

/**
 * JNI Entry: nativeGGUInit()
 * Creates and initializes the GGU native context.
 * Returns context pointer (or 0 on failure).
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_ggu_native_NativeBridge_nativeGGUInit(JNIEnv* env, jclass cls) {
    GGUNativeContext* ctx = new GGUNativeContext();
    memset(ctx, 0, sizeof(GGUNativeContext));
    
    // Initialize Vulkan instance (headless - no window extensions needed)
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "GGU";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "GGU Engine";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_3;
    
    VkInstanceCreateInfo instanceInfo{};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &appInfo;
    
    // No debug layers for server deployment
    const char* extensions[] = {};
    instanceInfo.enabledExtensionCount = 0;
    instanceInfo.enabledLayerCount = 0;
    
    VkResult result = vkCreateInstance(&instanceInfo, nullptr, &ctx->instance);
    if (result != VK_SUCCESS) {
        fprintf(stderr, "[GGU Native] Failed to create Vulkan instance: 0x%x\n", result);
        delete ctx;
        return 0;
    }
    
    // Find physical device with compute support
    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(ctx->instance, &deviceCount, nullptr);
    
    if (deviceCount == 0) {
        fprintf(stderr, "[GGU Native] No Vulkan physical devices found\n");
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return 0;
    }
    
    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(ctx->instance, &deviceCount, devices.data());
    
    // Select first device with compute queue support
    bool foundCompute = false;
    for (uint32_t i = 0; i < deviceCount && !foundCompute; i++) {
        VkQueueFamilyProperties props[16];
        uint32_t propCount = 16;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[i], &propCount, props);
        
        for (uint32_t j = 0; j < propCount; j++) {
            if (props[j].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                ctx->physicalDevice = devices[i];
                ctx->computeQueueFamilyIndex = j;
                foundCompute = true;
                break;
            }
        }
    }
    
    if (!foundCompute) {
        fprintf(stderr, "[GGU Native] No compute-queue capable device found\n");
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return 0;
    }
    
    // Get physical device properties for reporting
    VkPhysicalDeviceProperties devProps{};
    vkGetPhysicalDeviceProperties(ctx->physicalDevice, &devProps);
    
    fprintf(stdout, "[GGU Native] GPU: %s\n", devProps.deviceName);
    
    // Create logical device (compute queue only)
    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = ctx->computeQueueFamilyIndex;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &queuePriority;
    
    const char* deviceExtensions[] = {
        VK_KHR_SHADER_FLOAT_CONTROLS_EXTENSION_NAME
    };
    
    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.queueCreateCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    deviceInfo.enabledExtensionCount = 1;
    deviceInfo.ppEnabledExtensionNames = deviceExtensions;
    
    result = vkCreateDevice(ctx->physicalDevice, &deviceInfo, nullptr, &ctx->device);
    if (result != VK_SUCCESS) {
        fprintf(stderr, "[GGU Native] Failed to create Vulkan device: 0x%x\n", result);
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return 0;
    }
    
    // Get compute queue handle
    vkGetDeviceQueue(ctx->device, ctx->computeQueueFamilyIndex, 0, &ctx->computeQueue);
    
    // Create command pool for compute queues
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.queueFamilyIndex = ctx->computeQueueFamilyIndex;
    result = vkCreateCommandPool(ctx->device, &poolInfo, nullptr, &ctx->commandPool);
    
    // Initialize fence for synchronization
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    result = vkCreateFence(ctx->device, &fenceInfo, nullptr, &ctx->renderFence);
    
    ctx->initialized = true;
    
    fprintf(stdout, "[GGU Native] Context initialized successfully (ctx=%p)\n", (void*)ctx);
    return (jlong)ctx;
}

/**
 * JNI Entry: nativeCreateShaderModule(device, spirvCode)
 * Creates a VkShaderModule from SPIR-V binary code.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_ggu_gpu_ComputePipeline_nativeCreateShaderModule(
    JNIEnv* env, jobject obj, jlong device, jbyteArray spirvCode)
{
    VkDevice dev = (VkDevice)device;
    
    // Get SPIR-V data from Java byte array
    jsize size = env->GetArrayLength(spirvCode);
    jbyte* data = env->GetByteArrayElements(spirvCode, nullptr);
    
    VkShaderModuleCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    createInfo.codeSize = size;
    createInfo.pCode = reinterpret_cast<const uint32_t*>(data);
    
    VkShaderModule module;
    VkResult result = vkCreateShaderModule(dev, &createInfo, nullptr, &module);
    
    env->ReleaseByteArrayElements(spirvCode, data, JNI_ABORT);
    
    if (result != VK_SUCCESS) {
        fprintf(stderr, "[GGU Native] Failed to create shader module: 0x%x\n", result);
        return 0;
    }
    
    return (jlong)module;
}

/**
 * JNI Entry: nativeCreatePipelineLayout(device)
 * Creates VkPipelineLayout with descriptor set layout for SSBO bindings.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_ggu_gpu_ComputePipeline_nativeCreatePipelineLayout(
    JNIEnv* env, jobject obj, jlong device)
{
    VkDevice dev = (VkDevice)device;
    
    // Define descriptor set layout (3 SSBO bindings: input, output, uniforms)
    std::array<VkDescriptorSetLayoutBinding, 3> bindings{};
    
    // Binding 0: Input SSBO (task parameters)
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    
    // Binding 1: Output SSBO (block states)
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    
    // Binding 2: Uniform buffer
    bindings[2].binding = 2;
    buffers[2].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    bindings[2].descriptorCount = 1;
    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    
    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
    layoutInfo.pBindings = bindings.data();
    
    VkDescriptorSetLayout layout;
    VkResult result = vkCreateDescriptorSetLayout(dev, &layoutInfo, nullptr, &layout);
    
    // Create pipeline layout
    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &layout;
    
    VkPipelineLayout pipelineLayout;
    result = vkCreatePipelineLayout(dev, &pipelineLayoutInfo, nullptr, &pipelineLayout);
    
    if (result != VK_SUCCESS) {
        fprintf(stderr, "[GGU Native] Failed to create pipeline layout: 0x%x\n", result);
        return 0;
    }
    
    return (jlong)pipelineLayout;
}

/**
 * JNI Entry: nativeCreateComputePipeline(device, shaderModule, pipelineLayout)
 * Creates the VkComputePipeline from shader module and pipeline layout.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_ggu_gpu_ComputePipeline_nativeCreateComputePipeline(
    JNIEnv* env, jobject obj, jlong device, jlong shaderModule, jlong pipelineLayout)
{
    VkDevice dev = (VkDevice)device;
    VkShaderModule shaderMod = (VkShaderModule)shaderModule;
    VkPipelineLayout layout = (VkPipelineLayout)pipelineLayout;
    
    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = VkSpecializationInfo{}; // Simplified: no specialization constants
    pipelineInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipelineInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipelineInfo.stage.module = shaderMod;
    pipelineInfo.stage.pName = "main";
    pipelineInfo.layout = layout;
    
    VkPipeline pipeline;
    VkResult result = vkCreateComputePipelines(dev, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &pipeline);
    
    if (result != VK_SUCCESS) {
        fprintf(stderr, "[GGU Native] Failed to create compute pipeline: 0x%x\n", result);
        return 0;
    }
    
    return (jlong)pipeline;
}

/**
 * JNI Entry: nativeAllocBuffer(device, size, usageFlags)
 * Allocates a Vulkan buffer object with device-local memory.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_ggu_gpu_BufferManager_nativeAllocBuffer(
    JNIEnv* env, jobject obj, jlong device, jlong size, jint usageFlags)
{
    VkDevice dev = (VkDevice)device;
    
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = size;
    bufferInfo.usage = static_cast<VkBufferUsageFlags>(usageFlags);
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    
    VkBuffer buffer;
    VkResult result = vkCreateBuffer(dev, &bufferInfo, nullptr, &buffer);
    
    if (result != VK_SUCCESS) {
        fprintf(stderr, "[GGU Native] Failed to create buffer: 0x%x\n", result);
        return 0;
    }
    
    // Allocate device memory for the buffer
    VkMemoryRequirements memReq;
    vkGetBufferMemoryRequirements(dev, buffer, &memReq);
    
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    // Find memory type index with DEVICE_LOCAL property for GPU access
    uint32_t memTypeIndex = 0;
    // TODO: Query physical device memory types and find appropriate index
    
    VkDeviceMemory memory;
    result = vkAllocateMemory(dev, &allocInfo, nullptr, &memory);
    
    if (result != VK_SUCCESS) {
        fprintf(stderr, "[GGU Native] Failed to allocate buffer memory: 0x%x\n", result);
        vkDestroyBuffer(dev, buffer, nullptr);
        return 0;
    }
    
    // Bind memory to buffer
    vkBindBufferMemory(dev, buffer, memory, 0);
    
    return (jlong)buffer;
}

/**
 * JNI Entry: nativeFreeBuffer(device, buffer)
 * Frees a Vulkan buffer and its associated memory.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_ggu_gpu_BufferManager_nativeFreeBuffer(
    JNIEnv* env, jobject obj, jlong device, jlong buffer)
{
    VkDevice dev = (VkDevice)device;
    VkBuffer buf = (VkBuffer)buffer;
    
    // TODO: Track and free associated memory
    
    vkDestroyBuffer(dev, buf, nullptr);
}

/**
 * JNI Entry: nativeMapBufferToHost(buffer, size)
 * Maps a buffer to host-accessible memory for zero-copy DMA.
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_ggu_gpu_BufferManager_nativeMapBufferToHost(
    JNIEnv* env, jobject obj, jlong buffer, jlong size)
{
    // TODO: vkMapMemory on the buffer's device memory
    // Return a DirectByteBuffer via directByteBuffer(addr, size)
    
    return env->NewDirectByteBuffer(nullptr, 0); // Placeholder
}

/**
 * JNI Entry: nativeSubmitComputeDispatch(device, pipeline, layout, ...)
 * Submits a compute dispatch command to the GPU queue.
 * Returns a VkFence handle for synchronization.
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_ggu_gpu_ComputePipeline_nativeSubmitComputeDispatch(
    JNIEnv* env, jobject obj, jlong device, jlong pipeline, jlong layout,
    jobject inputBuffer, jlong outputSSBO, jint taskCount, jint wgX, jint wgY, jint wgZ)
{
    VkDevice dev = (VkDevice)device;
    VkPipeline computePipe = (VkPipeline)pipeline;
    
    // Create temporary command buffer for single-submission dispatch
    VkCommandBufferAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocInfo.commandPool = g_gguContext.commandPool;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;
    
    VkCommandBuffer cmdBuffer;
    vkAllocateCommandBuffers(dev, &allocInfo, &cmdBuffer);
    
    // Begin command buffer recording
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    vkBeginCommandBuffer(cmdBuffer, &beginInfo);
    
    // Dispatch compute shader
    vkCmdDispatch(cmdBuffer, wgX * taskCount, wgY, wgZ);
    
    // End recording
    vkEndCommandBuffer(cmdBuffer);
    
    // Submit to compute queue
    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &cmdBuffer;
    
    // Create fence for completion signaling
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    VkFence fence;
    vkCreateFence(dev, &fenceInfo, nullptr, &fence);
    
    vkQueueSubmit(g_gguContext.computeQueue, 1, &submitInfo, fence);
    
    // Clean up command buffer
    vkFreeCommandBuffers(dev, g_gguContext.commandPool, 1, &cmdBuffer);
    
    return (jlong)fence;
}

/**
 * JNI Entry: nativeWaitForFence(device, fence, timeoutMs)
 * Waits for a GPU fence to signal completion.
 * Returns true if completed within timeout.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ggu_gpu_ComputePipeline_nativeWaitForFence(
    JNIEnv* env, jobject obj, jlong device, jlong fence, jlong timeoutMs)
{
    VkDevice dev = (VkDevice)device;
    VkFence f = (VkFence)fence;
    
    // vkWaitForFences with specified timeout
    uint64_t timeoutNs = static_cast<uint64_t>(timeoutMs) * 1000000ULL;
    
    VkResult result = vkWaitForFences(dev, 1, &f, VK_TRUE, timeoutNs);
    
    return (jboolean)(result == VK_SUCCESS);
}

/**
 * JNI Entry: nativePollFence(device, fence)
 * Polls fence status without blocking. Returns true if signaled.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ggu_gpu_BufferManager_nativePollFence(
    JNIEnv* env, jobject obj, jlong device, jlong fence)
{
    VkDevice dev = (VkDevice)device;
    VkFence f = (VkFence)fence;
    
    VkResult result = vkGetFenceStatus(dev, f);
    return (jboolean)(result == VK_SUCCESS);
}

/**
 * JNI Entry: nativeDMAReadback(device, srcBuffer, byteCount)
 * Initiates DMA readback from GPU buffer to host memory.
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_ggu_gpu_BufferManager_nativeDMAReadback(
    JNIEnv* env, jobject obj, jlong device, jlong srcBuffer, jint byteCount)
{
    VkDevice dev = (VkDevice)device;
    VkBuffer buf = (VkBuffer)srcBuffer;
    
    // TODO: 
    // 1. Map buffer memory to host (vkMapMemory)
    // 2. Copy data to a newly allocated DirectByteBuffer
    // 3. Unmap and return the Java ByteBuffer
    
    return env->NewDirectByteBuffer(nullptr, 0); // Placeholder
}

/**
 * JNI Entry: nativeGetGPUName()
 * Returns the GPU device name as a Java String.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_ggu_native_NativeBridge_nativeGetGPUName(
    JNIEnv* env, jobject obj)
{
    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(g_gguContext.physicalDevice, &props);
    
    return env->NewStringUTF(props.deviceName);
}

/**
 * JNI Entry: nativeDestroyDevice(device)
 * Destroys the Vulkan device and cleans up resources.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_gpu_VulkanContext_nativeDestroyDevice(
    JNIEnv* env, jobject obj, jlong device)
{
    VkDevice dev = (VkDevice)device;
    
    if (dev != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(dev);
        
        // Destroy all resources first (simplified - real impl tracks all objects)
        vkDestroyDevice(dev, nullptr);
    }
}

// ============================================================
// JNI Registration
// 
// Called automatically when the native library is loaded.
// Registers all native method implementations with the JVM.
// ============================================================

static const JNINativeMethod g_methods[] = {
    {"nativeGGUInit", "()J", (void*)Java_com_ggu_native_NativeBridge_nativeGGUInit},
    {"nativeGetGPUName", "()Ljava/lang/String;", (void*)Java_com_ggu_native_NativeBridge_nativeGetGPUName},
    {"nativeDispatchBatch", "(J[BI)J", (void*)Java_com_ggu_gpu_ComputePipeline_nativeSubmitComputeDispatch},
    {"nativeWaitForCompletion", "(JJ)Z", (void*)Java_com_ggu_gpu_ComputePipeline_nativeWaitForFence},
    {"nativePollFence", "(JJ)Z", (void*)Java_com_ggu_gpu_BufferManager_nativePollFence},
    {"nativeAllocBuffer", "(JJI)J", (void*)Java_com_ggu_gpu_BufferManager_nativeAllocBuffer},
    {"nativeFreeBuffer", "(JJ)V", (void*)Java_com_ggu_gpu_BufferManager_nativeFreeBuffer},
    {"nativeMapBufferToHost", "(JJ)Ljava/nio/ByteBuffer;", (void*)Java_com_ggu_gpu_BufferManager_nativeMapBufferToHost},
    {"nativeDMAReadback", "(JJI)Ljava/nio/ByteBuffer;", (void*)Java_com_ggu_gpu_BufferManager_nativeDMAReadback},
    {"nativeCreateShaderModule", "(J[B)J", (void*)Java_com_ggu_gpu_ComputePipeline_nativeCreateShaderModule},
    {"nativeCreatePipelineLayout", "(J)J", (void*)Java_com_ggu_gpu_ComputePipeline_nativeCreatePipelineLayout},
    {"nativeCreateComputePipeline", "(JJJ)J", (void*)Java_com_ggu_gpu_ComputePipeline_nativeCreateComputePipeline},
    {"nativeWaitForFence", "(JJ)Z", (void*)Java_com_ggu_gpu_ComputePipeline_nativeWaitForFence},
    {"nativeDestroyDevice", "(J)V", (void*)Java_com_ggu_gpu_VulkanContext_nativeDestroyDevice},
};

/**
 * JNI_OnLoad - called when System.loadLibrary("ggu_native") is invoked.
 */
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_21) != JNI_OK) {
        return JNI_ERR;
    }
    
    // Find the Java class and register native methods
    jclass nativeBridgeClass = env->FindClass("com/ggu/native_/NativeBridge");
    if (nativeBridgeClass == nullptr) {
        return JNI_ERR;
    }
    
    jclass computePipelineClass = env->FindClass("com/ggu/gpu/ComputePipeline");
    if (computePipelineClass != nullptr) {
        env->RegisterNatives(computePipelineClass, g_methods, 
            sizeof(g_methods) / sizeof(g_methods[0]));
    }
    
    fprintf(stdout, "[GGU Native] JNI_OnLoad: %d methods registered\n", 
        static_cast<int>(sizeof(g_methods) / sizeof(g_methods[0])));
    
    return JNI_VERSION_1_21;
}

/**
 * JNI_OnUnload - called when the native library is unloaded.
 */
JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM* vm, void* reserved) {
    fprintf(stdout, "[GGU Native] Library unloaded, cleaning up\n");
    
    // Free all Vulkan resources
    if (g_gguContext.initialized) {
        vkDeviceWaitIdle(g_gguContext.device);
        
        vkDestroyCommandPool(g_gguContext.device, g_gguContext.commandPool, nullptr);
        vkDestroyFence(g_gguContext.device, g_gguContext.renderFence, nullptr);
        vkDestroyPipelineLayout(g_gguContext.device, g_gguContext.pipelineLayout, nullptr);
        vkDestroyPipeline(g_gguContext.device, g_gguContext.computePipeline, nullptr);
        vkDestroyShaderModule(g_gguContext.device, g_gguContext.computeShaderModule, nullptr);
        vkDestroyDevice(g_gguContext.device, nullptr);
        vkDestroyInstance(g_gguContext.instance, nullptr);
        
        g_gguContext.initialized = false;
    }
}