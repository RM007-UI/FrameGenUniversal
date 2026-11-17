package org.framegen.universal.engine
data class GeneratedFrame(val pixels: IntArray, val width: Int, val height: Int, val processingMillis: Long)
interface FrameInterpolationEngine : AutoCloseable {
    val engineId: String
    val displayName: String
    fun interpolate(previous: IntArray, current: IntArray, width: Int, height: Int): GeneratedFrame?
    override fun close() = Unit
}
