# Changelog

## 0.4.0-gpu-preview
- Added Android 14+ MediaProjection capture and a foreground service.
- Added a trusted AccessibilityService output layer with no window-content retrieval or gesture capability.
- Added a CPU bidirectional block-motion reference engine and automatic Vulkan/CPU backend selection.
- Added an independent native Vulkan compute engine using storage buffers and a build-embedded GLSL shader.
- Added refresh-aware midpoint scheduling, deadline rejection, source/generated FPS and duplicate/drop counters.
- Added JUnit regression tests and GitHub Actions APK build workflow.
- No external DLL, model, shader archive, network download or user file is required.

The Vulkan backend is independent project code. It is not a direct Win-FG or WFG/DIS port.
