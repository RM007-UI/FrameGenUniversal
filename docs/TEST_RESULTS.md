# Validation record

## Source-level checks
- Kotlin core engine is covered by JUnit tests for identical frames, uniform midpoint blend, translated-object midpoint centroid and invalid inputs.
- Scheduler tests cover 30 FPS to 60 Hz, insufficient headroom, long stalls and invalid intervals.
- tools/verify_source.py checks Android XML/permissions, presenter configuration, native source presence and shader build integration.

## Not yet verified
- The full Android Gradle/NDK/CMake build has not been run from this environment.
- GLSL compilation and SPIR-V validation must complete in GitHub Actions.
- Vulkan dispatch, storage-buffer synchronization, MediaProjection and Accessibility overlay have not been tested on a physical device.
- Snapdragon 720G / Adreno 618 performance, thermals, output quality and touch-through remain unverified.
- The direct upstream Win-FG and WFG/DIS ports are not part of this build.

This is an experimental source release, not a production-validated APK.
