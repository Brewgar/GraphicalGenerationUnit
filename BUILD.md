# GGU - GraphicalGenerationUnit Build Guide

## Prerequisites

### Required Software
1. **Java 21+** (JDK 21 recommended)
   - Download: https://adoptium.net/
   - Verify: `java --version`

2. **Gradle 8+** (or use Gradle wrapper)
   - The project includes a gradle wrapper that will download it automatically

3. **Vulkan SDK 1.3+** (for native library compilation)
   - Download: https://vulkan.lunarg.com/sdk/home
   - Windows installer includes everything needed
   - Default install path: `C:\VulkanSDK\1.3.x.x`

4. **MinGW-w64 or MSVC Compiler**
   - MinGW-w64 (recommended for simplicity): https://www.mingw-w64.org/
   - Or use Visual Studio Build Tools: https://visualstudio.microsoft.com/visual-cpp-build-tools/

### Optional
- **SPIR-V Compiler** (`glslc` from Vulkan SDK) - for compiling GLSL to SPIR-V
- **Minecraft 1.21.4** - for testing with Fabric

---

## Build Steps

### Step 1: Clone/Navigate to Project Directory
```bash
cd C:\Users\tahae\OneDrive\Desktop\GraphicalGenerationUnit
```

### Step 2: Set Up Environment Variables
```cmd
:: Set Vulkan SDK path (adjust if installed elsewhere)
set VULKAN_SDK=C:\VulkanSDK\1.3.290.0

:: Ensure Java is in PATH
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21...
```

### Step 3: Compile the Native Library (C++/Vulkan)

#### Option A: Using MinGW-w64 (Recommended)
```cmd
cd native\src
call build.bat
cd ..\..\..
```

This compiles `gpu_native.cpp` into `gpu_native.dll` and places it in:
`src/main/resources/ggu/native/lib/`

#### Option B: Using Visual Studio (MSVC)
```cmd
:: Open "x64 Native Tools Command Prompt for VS 2022"
cd native\src
cl /O2 /std:c++17 ^
   /I"%VULKAN_SDK%\Include" ^
   /I"%JAVA_HOME%\include" ^
   /I"%JAVA_HOME%\include\win32" ^
   /LD ^
   /Fe:"..\..\src\main\resources\ggu\native\lib\gpu_native.dll" ^
   gpu_native.cpp ^
   "%VULKAN_SDK%\Lib\vulkan-1.lib"
```

### Step 4: Compile GLSL Shader to SPIR-V (Optional - for development)
```cmd
:: Using glslc from Vulkan SDK
"%VULKAN_SDK%\Bin\glslc.exe" ^
    -O3 ^
    -c ^
    shaders/noise_compute.glsl ^
    -o shaders/noise_compute.spv

:: Place compiled shader in resources
mkdir src\main\resources\ggu\shaders
copy shaders\noise_compute.spv src\main\resources\ggu\shaders\
```

### Step 5: Build the Fabric Mod with Gradle

#### Option A: Using Gradle Wrapper (Recommended)
```cmd
:: From project root directory
gradlew build
```

This will:
1. Compile all Java sources
2. Package into a JAR file
3. Place output in `build/libs/`

#### Option B: Clean Build
```cmd
gradlew clean build
```

### Step 6: Find the Built Mod JAR
After successful build, the mod JAR will be at:
```
build\libs\GraphicalGenerationUnit-1.0.0.jar
```

---

## Installation into Minecraft

### Method 1: Manual Installation
1. Copy the JAR to your Minecraft mods folder:
   ```cmd
   copy build\libs\GraphicalGenerationUnit-1.0.0.jar "%APPDATA%\.minecraft\mods\"
   ```

2. Ensure you have **Fabric Loader** installed (https://fabricmc.net/use/installer/)

3. Optionally install **Fabric API** (required dependency)

4. Launch Minecraft with the Fabric profile

### Method 2: Using Fabric Launcher
1. Install [Fabric Launcher](https://fabricmc.net/use/installer/)
2. Create a new Minecraft profile with Fabric loader
3. Drop `GraphicalGenerationUnit-1.0.0.jar` into `.minecraft/mods/`
4. Launch

---

## Running in Development Environment

### Run Local Server (for testing)
```cmd
gradlew runServer
```

### Run Client (for testing)
```cmd
gradlew runClient
```

---

## Troubleshooting

### "Vulkan not found" during native build
- Ensure Vulkan SDK is installed
- Verify `VULKAN_SDK` environment variable points to correct path
- Check that `%VULKAN_SDK%\Lib\vulkan-1.lib` exists

### "g++ not found" during native build
- Install MinGW-w64 and add to PATH, OR
- Use Visual Studio Build Tools instead

### "Java class file has wrong version 61.0"
- You need Java 21 (not just Java 17)
- Set `JAVA_HOME` to your JDK 21 installation

### Gradle download fails
```cmd
:: Force Gradle wrapper re-download
gradlew wrapper --gradle-version 8.5 --distribution-type all
```

### Missing Vulkan drivers
- Install latest GPU drivers from NVIDIA/AMD/Intel
- Verify Vulkan support: `vkcube --list-adapters` (from Vulkan SDK)

---

## Build Output Reference

| File | Location | Description |
|------|----------|-------------|
| Mod JAR | `build/libs/GraphicalGenerationUnit-1.0.0.jar` | Main mod file for Minecraft |
| Native DLL | `src/main/resources/ggu/native/lib/gpu_native.dll` | Vulkan compute library |
| Config | `config/ggu/ggu-config.properties` | Runtime config (auto-generated on first run) |

---

## Full Build One-Liner (Windows CMD)
```cmd
cd C:\Users\tahae\OneDrive\Desktop\GraphicalGenerationUnit && ^
set VULKAN_SDK=C:\VulkanSDK\1.3.290.0 && ^
cd native\src && call build.bat && cd ..\..\.. && ^
gradlew clean build