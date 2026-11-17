#!/usr/bin/env python3
"""Static safety/build invariants for FrameGen Universal."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
A="{http://schemas.android.com/apk/res/android}"
def fail(message):
 print("FAIL:",message,file=sys.stderr);raise SystemExit(1)
try:
 manifest=ET.parse(ROOT/"app/src/main/AndroidManifest.xml").getroot()
 access=ET.parse(ROOT/"app/src/main/res/xml/frame_overlay_accessibility_service.xml").getroot()
 for path in (ROOT/"app/src/main/res").rglob("*.xml"):ET.parse(path)
except (ET.ParseError,OSError) as exc:fail("Android XML validation failed: "+str(exc))
permissions={e.attrib.get(A+"name") for e in manifest.findall("uses-permission")}
if "android.permission.INTERNET" in permissions:fail("Unexpected INTERNET permission")
if "android.permission.SYSTEM_ALERT_WINDOW" in permissions:fail("Use the trusted accessibility presenter instead of SYSTEM_ALERT_WINDOW")
if "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" not in permissions:fail("Missing MediaProjection foreground permission")
services=manifest.find("application").findall("service")
if sum(s.attrib.get(A+"foregroundServiceType")=="mediaProjection" for s in services)!=1:fail("Expected one MediaProjection foreground service")
access_services=[s for s in services if s.attrib.get(A+"permission")=="android.permission.BIND_ACCESSIBILITY_SERVICE"]
if len(access_services)!=1:fail("Expected one trusted presenter AccessibilityService")
if access.attrib.get(A+"canRetrieveWindowContent")!="false":fail("Presenter must not read window content")
if access.attrib.get(A+"canPerformGestures")!="false":fail("Presenter must not perform gestures")
required=[
 "app/src/main/java/org/framegen/universal/engine/BlockMotionFrameGenerator.kt",
 "app/src/main/java/org/framegen/universal/engine/NativeGpuEngine.kt",
 "app/src/main/java/org/framegen/universal/capture/FrameCaptureService.kt",
 "app/src/main/java/org/framegen/universal/capture/FrameOverlayAccessibilityService.kt",
 "app/src/main/cpp/vulkan_frame_engine.cpp",
 "app/src/main/cpp/device_probe.cpp",
 "app/src/main/cpp/shaders/motion_midpoint.comp",
 "tools/embed_vulkan_shader.py",
]
for name in required:
 p=ROOT/name
 if not p.is_file() or p.stat().st_size==0:fail("Missing source file: "+name)
cmake=(ROOT/"app/src/main/cpp/CMakeLists.txt").read_text()
for token in ("glslangValidator","embed_vulkan_shader.py","VK_USE_PLATFORM_ANDROID_KHR","vulkan_frame_engine.cpp"):
 if token not in cmake:fail("CMake missing "+token)
shader=(ROOT/"app/src/main/cpp/shaders/motion_midpoint.comp").read_text()
if "local_size_x=8" not in shader or "binding=2" not in shader:fail("Shader interface changed")
native=(ROOT/"app/src/main/cpp/vulkan_frame_engine.cpp").read_text()
for token in ("vkQueueSubmit","vkWaitForFences","VK_ACCESS_HOST_WRITE_BIT","MOTION_MIDPOINT"):
 if token not in native:fail("Native Vulkan engine missing "+token)
print("PASS: Android XML and permissions")
print("PASS: minimal AccessibilityService presentation flags")
print("PASS: Kotlin capture/CPU engine and native Vulkan sources")
print("PASS: shader compile/embed and Vulkan synchronization markers")
