package org.framegen.universal.engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
class FramePresentationSchedulerTest{
 @Test fun thirtyFpsSourceCanFillSixtyHzPanel(){
  val schedule=FramePresentationScheduler.forPair(1_000_000_000L/30L,1_000_000_000L/60L)
  checkNotNull(schedule);assertEquals(17L,schedule.midpointDelayMs);assertEquals(17L,schedule.holdDurationMs)
 }
 @Test fun fortyFpsSourceHasNoUsefulHeadroomOnSixtyHz(){assertNull(FramePresentationScheduler.forPair(1_000_000_000L/40L,1_000_000_000L/60L))}
 @Test fun longStallsAreRejected(){assertNull(FramePresentationScheduler.forPair(1_000_000_000L/10L,1_000_000_000L/60L))}
 @Test fun invalidIntervalsAreRejected(){assertNull(FramePresentationScheduler.forPair(0L,16_666_667L));assertNull(FramePresentationScheduler.forPair(33_333_333L,0L))}
}
