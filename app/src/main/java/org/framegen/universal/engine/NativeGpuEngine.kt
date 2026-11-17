package org.framegen.universal.engine
internal object NativeGpuEngine{
 init{System.loadLibrary("framegen_probe")}
 external fun nativeInitialize():String
 external fun nativeLastError():String
 external fun nativeInterpolate(previous:IntArray,current:IntArray,width:Int,height:Int):IntArray?
 external fun nativeShutdown()
}
class VulkanComputeFrameGenerator:FrameInterpolationEngine{
 override val engineId="vulkan-block-motion";override val displayName="Vulkan Block Motion"
 @Volatile private var initialized=false
 @Volatile var status="Vulkan not initialized";private set
 @Synchronized private fun ensureInitialized():Boolean{
  if(initialized)return true
  status=runCatching{NativeGpuEngine.nativeInitialize()}.getOrElse{"Vulkan unavailable: "+(it.message?:it.javaClass.simpleName)}
  initialized=status.startsWith("GPU ready:")
  return initialized
 }
 override fun interpolate(previous:IntArray,current:IntArray,width:Int,height:Int):GeneratedFrame?{
  if(!ensureInitialized())return null
  val start=System.nanoTime()
  val pixels=runCatching{NativeGpuEngine.nativeInterpolate(previous,current,width,height)}.getOrNull()
  if(pixels==null||pixels.size.toLong()!=width.toLong()*height){status=runCatching{NativeGpuEngine.nativeLastError()}.getOrDefault("Vulkan interpolation failed");return null}
  return GeneratedFrame(pixels,width,height,(System.nanoTime()-start)/1_000_000L)
 }
 override fun close(){runCatching{NativeGpuEngine.nativeShutdown()};initialized=false;status="Vulkan backend stopped"}
}
class AutoFrameInterpolationEngine:FrameInterpolationEngine{
 private val gpu=VulkanComputeFrameGenerator();private val cpu=BlockMotionFrameGenerator()
 @Volatile private var failed=false;@Volatile private var active="Probing Vulkan"
 @Volatile var status=active;private set
 override val engineId get()=if(failed)cpu.engineId else gpu.engineId
 override val displayName get()=active
 override fun interpolate(previous:IntArray,current:IntArray,width:Int,height:Int):GeneratedFrame?{
  if(!failed){
   val result=gpu.interpolate(previous,current,width,height)
   if(result!=null){active=gpu.displayName;status=gpu.status;return result}
   failed=true;active="CPU Block Motion (fallback)";status="Vulkan unavailable; CPU fallback: "+gpu.status;runCatching{gpu.close()}
  }
  return cpu.interpolate(previous,current,width,height)
 }
 override fun close(){runCatching{gpu.close()};runCatching{cpu.close()}}
}
