@echo off
REM GGU Native Build Script (Windows)
REM Requires: Vulkan SDK, MinGW-w64 or MSVC

set VULKAN_SDK=%VULKAN_SDK%
if "%VULKAN_SDK%"=="" set VULKAN_SDK=C:\VulkanSDK\1.3.290.0

echo [GGU] Building native library...

REM Compile with MinGW-w64 (preferred for cross-platform)
g++ -O2 -std=c++17 ^
    -I"%VULKAN_SDK%\Include" ^
    -I"%JAVA_HOME%\include" ^
    -I"%JAVA_HOME%\include\win32" ^
    -shared ^
    -o "../src/main/resources/ggu/native/lib/gpu_native.dll" ^
    gpu_native.cpp ^
    "%VULKAN_SDK%\Lib\vulkan-1.lib"

if %errorlevel% equ 0 (
    echo [GGU] Native library built successfully: gpu_native.dll
) else (
    echo [GGU] Build FAILED with error level: %errorlevel%
    exit /b %errorlevel%
)