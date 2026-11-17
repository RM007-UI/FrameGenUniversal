package org.framegen.universal.capture
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
object CaptureStats {
 private val captured=AtomicLong();private val unique=AtomicLong();private val duplicate=AtomicLong()
 private val dropped=AtomicLong();private val generated=AtomicLong();private val processing=AtomicLong()
 private var sampleAt=0L;private var lastUnique=0L;private var lastGenerated=0L
 @Synchronized fun reset(){captured.set(0);unique.set(0);duplicate.set(0);dropped.set(0);generated.set(0);processing.set(0);sampleAt=SystemClock.elapsedRealtime();lastUnique=0;lastGenerated=0}
 fun onCaptured(){captured.incrementAndGet()};fun onUnique(){unique.incrementAndGet()};fun onDuplicate(){duplicate.incrementAndGet()}
 fun onDropped(){dropped.incrementAndGet()};fun onGenerated(ms:Long){generated.incrementAndGet();processing.addAndGet(ms.coerceAtLeast(0))}
 @Synchronized fun snapshot():Snapshot{
  val now=SystemClock.elapsedRealtime();val elapsed=(now-sampleAt).coerceAtLeast(1);val u=unique.get();val g=generated.get()
  val source=(u-lastUnique).coerceAtLeast(0)*1000.0/elapsed;val output=(g-lastGenerated).coerceAtLeast(0)*1000.0/elapsed
  sampleAt=now;lastUnique=u;lastGenerated=g
  return Snapshot(source,output,if(g==0L)0.0 else processing.get().toDouble()/g,captured.get(),u,duplicate.get(),dropped.get(),g)
 }
 data class Snapshot(val sourceFps:Double,val generatedFps:Double,val averageProcessingMs:Double,val capturedFrames:Long,val uniqueFrames:Long,val duplicateFrames:Long,val droppedFrames:Long,val generatedFrames:Long)
}
