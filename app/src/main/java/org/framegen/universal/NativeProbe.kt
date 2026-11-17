package org.framegen.universal
object NativeProbe {
    init { System.loadLibrary("framegen_probe") }
    external fun inspectVulkan(): String
}
