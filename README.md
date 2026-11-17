# FrameGen Universal

**v0.4.0-gpu-preview · Android 14+**

A self-contained Android frame-interpolation prototype. The project contains Android MediaProjection capture, a trusted AccessibilityService presentation layer, a CPU reference engine, and an independent Vulkan compute implementation.

## No external files
The app does not require Lossless.dll, proprietary shader packs, models, Steam files, game files, user-selected assets, or runtime downloads. The GLSL compute shader is compiled into SPIR-V during the APK build and embedded into the native library.

## Current feature set
- Android 14+ app-window capture using the MediaProjection user-choice picker.
- Foreground mediaProjection service with persistent stop notification.
- ImageReader capture with stride validation, bounded pixel count, resize handling, and a newest-frame queue.
- Trusted TYPE_ACCESSIBILITY_OVERLAY presenter. Its AccessibilityService configuration disables window-content retrieval and gesture support; its callback ignores event data.
- CPU bidirectional block-motion reference/fallback.
- Native Vulkan compute engine using storage buffers and a bounded local motion search shader.
- Automatic Vulkan-first backend selection with CPU fallback on Vulkan failure.
- Refresh-aware midpoint scheduling, late-frame rejection, source/generated FPS, duplicate/drop counters, and processing-time stats.
- Read-only Vulkan device capability report.
- No INTERNET or SYSTEM_ALERT_WINDOW permission.

## Important distinction
The included Vulkan engine is original project code. It is **not** a direct port of Win-FG or WFG/DIS. Those upstream repositories were reviewed as possible future backends, but their host-specific adapters/build systems have not been ported. The app does not claim DLSS/LSFG-level quality or a doubled hardware-presented frame rate.

## First-time setup
1. Install the debug APK after a successful GitHub Actions build.
2. Open FrameGen and select **Open Accessibility settings**.
3. Enable **FrameGen frame presentation**. This service only provides the trusted output window; it cannot read window content or inject gestures.
4. Return to the app and tap **Start frame generation**.
5. Approve Android's screen-capture consent and select the individual game/app window where possible.
6. Stop from the app or the persistent notification.

This is an experimental sideload/testing use of AccessibilityService; distribution through app stores requires separate policy review.

## Build
Requirements:
- JDK 17
- Android SDK Platform 35
- Android Gradle Plugin 8.7.3 / Gradle 8.9
- Android NDK 27.0.12077973
- CMake 3.22.1
- glslangValidator and Python 3 on the build host

GitHub Actions installs these tools, runs the source verifier and JVM tests, builds app-debug.apk, and uploads the APK as a workflow artifact. Open this repository in Android Studio and build the app debug variant, or use the Actions artifact after the workflow succeeds.

## Limitations and test status
- Capture resolution is capped at 480 px on the long side.
- The Vulkan backend uploads captured frames to CPU-visible storage buffers, dispatches compute, then reads the result back for the overlay. It does not intercept the game's swapchain, and the staging/overlay path adds latency.
- The local motion search is deliberately bounded; occlusion, fast motion, particles, small text, and HUD elements may show artifacts.
- CPU fallback is a reference engine and may miss frame deadlines.
- Generated FPS counts successful interpolation results, not hardware-confirmed presented frames.
- The target Redmi Note 9 Pro / Snapdragon 720G / Adreno 618 has not yet been physically tested.
- The Android/NDK/GLSL build must be confirmed by GitHub Actions before treating this snapshot as a working APK.

See docs/ for architecture, roadmap, test record, and third-party notices.
