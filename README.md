# FrameGen Universal

Self-contained Android frame-interpolation research prototype.

The project includes Android MediaProjection capture, a minimal trusted AccessibilityService presentation window, a CPU reference interpolation engine, and an independent Vulkan compute prototype. GLSL shaders are compiled to SPIR-V at build time and embedded in the native library; no external DLL, model, shader archive, network download, or user-selected file is required.

**Important:** the built-in Vulkan engine is original project code. It is not a direct port of Win-FG or WFG/DIS. Those upstream projects were reviewed as future integration candidates; their code is not included in this repository.

Current state: experimental source. The APK and Vulkan path require GitHub Actions/Android SDK build validation and physical testing, especially on Redmi Note 9 Pro / Snapdragon 720G / Adreno 618.
