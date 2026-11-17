package org.framegen.universal.engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
class BlockMotionFrameGeneratorTest{
 @Test fun identicalFramesRemainUnchanged(){
  val w=64;val h=64;val frame=IntArray(w*h){0xff3478a0.toInt()}
  val result=BlockMotionFrameGenerator().interpolate(frame,frame.copyOf(),w,h)
  assertNotNull(result);assertTrue(frame.contentEquals(result!!.pixels))
 }
 @Test fun uniformColorChangeProducesMidpoint(){
  val w=64;val h=64;val a=IntArray(w*h){0xff000000.toInt()};val b=IntArray(w*h){0xff646464.toInt()}
  val result=BlockMotionFrameGenerator().interpolate(a,b,w,h);assertNotNull(result)
  val p=result!!.pixels[(h/2)*w+w/2]
  assertEquals(50,(p ushr 16)and 255);assertEquals(50,(p ushr 8)and 255);assertEquals(50,p and 255)
 }
 @Test fun translatedObjectMovesTowardMidpoint(){
  val w=128;val h=96;val a=IntArray(w*h){0xff000000.toInt()};val b=IntArray(w*h){0xff000000.toInt()}
  for(y in 24 until 64)for(x in 20 until 48)a[y*w+x]=0xffffffff.toInt()
  for(y in 24 until 64)for(x in 28 until 56)b[y*w+x]=0xffffffff.toInt()
  val pixels=BlockMotionFrameGenerator().interpolate(a,b,w,h)!!.pixels
  var n=0L;var sum=0L
  for(y in 0 until h)for(x in 0 until w)if((pixels[y*w+x] and 255)>128){n++;sum+=x}
  val centroid=sum.toDouble()/n.toDouble()
  assertTrue("Expected midpoint centroid near 38, got "+centroid,centroid in 35.0..41.0)
 }
 @Test fun invalidAndOversizedInputsAreRejected(){
  val engine=BlockMotionFrameGenerator()
  assertEquals(null,engine.interpolate(IntArray(4),IntArray(4),2,2))
  assertEquals(null,engine.interpolate(IntArray(0),IntArray(0),Int.MAX_VALUE,Int.MAX_VALUE))
  assertEquals(null,engine.interpolate(IntArray(0),IntArray(0),4096,2048))
 }
 @Test(expected=IllegalArgumentException::class) fun unboundedSearchSettingsAreRejected(){BlockMotionFrameGenerator(searchRadius=1000)}
}
