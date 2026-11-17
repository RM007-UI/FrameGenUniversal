# Roadmap

## Implemented in 0.4.0 preview
- [x] MediaProjection app-window capture and foreground service.
- [x] Trusted accessibility output window.
- [x] CPU block-motion fallback.
- [x] Independent Vulkan storage-buffer compute prototype.
- [x] Build-time shader compilation and embedding.
- [x] Automatic GPU-to-CPU fallback and timing/drop telemetry.
- [x] JUnit tests and CI build workflow.

## Remaining
- [ ] Run Android Gradle/NDK/GLSL build on GitHub Actions and fix any CI errors.
- [ ] Test MediaProjection and touch-through on Android 14/15 hardware.
- [ ] Benchmark Vulkan engine, thermal limits and image artifacts on Adreno 618.
- [ ] Improve optical-flow quality, occlusion handling, text/HUD preservation and pacing.
- [ ] Directly port Win-FG with its MIT and AMD FidelityFX notices, if a clean Android adapter can be built.
- [ ] Adapt the standalone MIT WFG/DIS subtree without importing GPL WinNative parent compositor code.
- [ ] Add production-quality in-swapchain integration only where platform APIs permit.
- [ ] Produce a signed release APK after device validation.
