# Architecture

## Runtime
1. MainActivity obtains explicit MediaProjection consent for each session.
2. FrameCaptureService starts as a mediaProjection foreground service and captures the selected app window through ImageReader.
3. A bounded latest-frame queue feeds AutoFrameInterpolationEngine.
4. VulkanComputeFrameGenerator is tried first. It uploads two ARGB frames into host-visible Vulkan storage buffers, runs motion_midpoint.comp, reads the generated frame back, and returns it to Kotlin.
5. If Vulkan initialization or dispatch fails, BlockMotionFrameGenerator is used as the CPU reference fallback.
6. FramePresentationScheduler rejects source rates without display headroom and rejects long capture stalls. The generated midpoint is briefly displayed over the current game frame, then the overlay clears.

## Vulkan
The native engine owns its Vulkan instance, compute queue, device, storage buffers, descriptor set, pipeline, command pool/buffer, and fence. The GLSL shader searches a bounded local motion offset in both directions, checks forward/backward consistency, and generates a midpoint. The shader is compiled to SPIR-V by glslangValidator during the Android build, then embedded in the native library by tools/embed_vulkan_shader.py. No shader is loaded from external storage at runtime.

This is a compact independent GPU prototype, not the upstream Win-FG or WFG/DIS code. It uses CPU-visible staging/readback buffers and does not intercept the game's swapchain, so it adds latency and is not equivalent to a production in-swapchain frame-generation layer.

## Android presentation/privacy
The presenter uses TYPE_ACCESSIBILITY_OVERLAY. Its config sets canRetrieveWindowContent=false and canPerformGestures=false. Its accessibility event callback intentionally reads no event data. The service only hosts the output ImageView. The user must explicitly enable it in Android Accessibility settings.

No INTERNET or SYSTEM_ALERT_WINDOW permission is requested. Captured pixels remain in RAM. The app does not read game memory, inject input, bypass anti-cheat or upload captures.

## Limitations
- Android 14/API 34+ is required for the user-choice app-window capture flow.
- Capture is limited to 480 pixels on the long side.
- Motion search is deliberately bounded and can artifact on occlusion, particles, fast camera movement, small text, and HUD overlays.
- CPU staging/readback and overlay presentation add latency.
- Generated FPS is not hardware-confirmed presented FPS.
- The target Redmi Note 9 Pro / Adreno 618 has not yet been physically tested.
