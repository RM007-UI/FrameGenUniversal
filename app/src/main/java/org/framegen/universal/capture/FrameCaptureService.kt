package org.framegen.universal.capture
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import org.framegen.universal.MainActivity
import org.framegen.universal.engine.AutoFrameInterpolationEngine
import org.framegen.universal.engine.FrameInterpolationEngine
import org.framegen.universal.engine.FramePresentationScheduler
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

class FrameCaptureService:Service(){
 private val main=Handler(Looper.getMainLooper())
 private val worker=Executors.newSingleThreadExecutor()
 private val busy=AtomicBoolean(false)
 private val epoch=AtomicLong(0)
 private val pending=AtomicReference<Frame?>(null)
 private val frameLock=Any()
 private lateinit var engine:FrameInterpolationEngine
 private var captureThread:HandlerThread?=null
 private var captureHandler:Handler?=null
 private var projection:MediaProjection?=null
 private var projectionCallback:MediaProjection.Callback?=null
 private var display:VirtualDisplay?=null
 private var reader:ImageReader?=null
 private var oldPixels:IntArray?=null
 private var oldWidth=0;private var oldHeight=0;private var oldTimestamp=0L;private var oldHash=0
 private var density=0
 @Volatile private var stopping=false
 private var clearTask:Runnable?=null
 private data class Frame(val pixels:IntArray,val width:Int,val height:Int,val timestampNs:Long,val hash:Int,val epoch:Long)
 private data class Previous(val pixels:IntArray,val width:Int,val height:Int,val timestampNs:Long)

 override fun onBind(intent:Intent?):IBinder?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  when(intent?.action){ACTION_STOP->stopCapture(true);ACTION_START->startCapture(intent)}
  return START_NOT_STICKY
 }
 private fun startCapture(intent:Intent){
  if(projection!=null)return
  stopping=false;lastError=null
  if(!FrameOverlayAccessibilityService.isConnected()){
   lastError="Enable FrameGen frame presentation in Accessibility settings first.";stopSelf();return
  }
  val code=intent.getIntExtra(EXTRA_RESULT_CODE,0)
  val data=intent.getParcelableExtra(EXTRA_RESULT_DATA,Intent::class.java)
  if(code==0||data==null){lastError="Screen-capture consent was not received.";stopSelf();return}
  try{
   createChannel()
   startForeground(NOTIFICATION_ID,buildNotification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
   val manager=getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
   projection=manager.getMediaProjection(code,data)?:error("MediaProjection could not be created")
   CaptureStats.reset();engine=AutoFrameInterpolationEngine();engineStatus="Vulkan probing on first frame"
   captureThread=HandlerThread("FrameGen-Capture").also{it.start()};captureHandler=Handler(captureThread!!.looper)
   density=resources.displayMetrics.densityDpi
   val bounds=getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
   val size=scaledSize(bounds.width(),bounds.height())
   configureReader(size.first,size.second,false)
   val cb=object:MediaProjection.Callback(){
    override fun onStop(){stopCapture(false)}
    override fun onCapturedContentResize(width:Int,height:Int){
     val target=scaledSize(width,height)
     captureHandler?.post{
      if(!stopping&&target.first>0&&target.second>0&&(target.first!=reader?.width||target.second!=reader?.height))
       configureReader(target.first,target.second,true)
     }
    }
    override fun onCapturedContentVisibilityChanged(isVisible:Boolean){
     if(!isVisible)main.post{cancelOutput();FrameOverlayAccessibilityService.hide()}
    }
   }
   projectionCallback=cb;projection!!.registerCallback(cb,main)
   display=projection!!.createVirtualDisplay("FrameGenUniversal",reader!!.width,reader!!.height,density,
    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,captureHandler)?:error("VirtualDisplay failed")
   main.post{FrameOverlayAccessibilityService.show()}
   active=true
  }catch(e:Throwable){lastError="Capture start failed: "+(e.message?:e.javaClass.simpleName);stopCapture(true)}
 }
 private fun configureReader(width:Int,height:Int,attach:Boolean){
  val current=epoch.incrementAndGet();pending.set(null);synchronized(frameLock){clearPrevious()}
  main.post{if(current==epoch.get()){cancelOutput();FrameOverlayAccessibilityService.hide()}}
  val replacement=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3)
  replacement.setOnImageAvailableListener({onImageAvailable(it,current)},captureHandler)
  val previous=reader;reader=replacement
  if(attach){display?.resize(width,height,density);display?.setSurface(replacement.surface)}
  previous?.close()
 }
 private fun clearPrevious(){oldPixels=null;oldWidth=0;oldHeight=0;oldTimestamp=0;oldHash=0}
 private fun scaledSize(width:Int,height:Int):Pair<Int,Int>{
  if(width<=0||height<=0)return 480 to 270
  val ratio=min(1f,MAX_SIDE.toFloat()/max(width,height))
  return max(2,(width*ratio).toInt()) to max(2,(height*ratio).toInt())
 }
 private fun onImageAvailable(source:ImageReader,currentEpoch:Long){
  if(stopping||currentEpoch!=epoch.get())return
  val image=try{source.acquireLatestImage()}catch(_:IllegalStateException){null}?:return
  val frame=try{readImage(image,currentEpoch)}catch(e:Throwable){
   lastError="Frame read failed: "+(e.message?:e.javaClass.simpleName);null
  }finally{image.close()}?:return
  if(stopping||currentEpoch!=epoch.get())return
  CaptureStats.onCaptured()
  if(pending.getAndSet(frame)!=null)CaptureStats.onDropped()
  processPending()
 }
 private fun readImage(image:Image,currentEpoch:Long):Frame{
  val plane=image.planes.firstOrNull()?:error("Missing image plane")
  val buffer=plane.buffer.duplicate();val w=image.width;val h=image.height
  val row=plane.rowStride;val pixel=plane.pixelStride
  require(w>0&&h>0&&pixel>=4&&row.toLong()>=w.toLong()*pixel)
  val count=w.toLong()*h;require(count<=MAX_PIXELS&&count<=Int.MAX_VALUE)
  val base=buffer.position();val required=base.toLong()+(h-1L)*row+(w-1L)*pixel+4
  require(required<=buffer.limit().toLong())
  val output=IntArray(count.toInt());var hash=0x811c9dc5.toInt()
  for(y in 0 until h)for(x in 0 until w){
   val i=base+y*row+x*pixel
   val r=buffer.get(i).toInt() and 255;val g=buffer.get(i+1).toInt() and 255
   val b=buffer.get(i+2).toInt() and 255;val a=buffer.get(i+3).toInt() and 255
   val color=(a shl 24)or(r shl 16)or(g shl 8)or b
   output[y*w+x]=color;hash=(hash xor color)*16777619
  }
  val timestamp=image.timestamp.takeIf{it>0L}?:System.nanoTime()
  return Frame(output,w,h,timestamp,hash,currentEpoch)
 }
 private fun processPending(){
  if(stopping||!busy.compareAndSet(false,true))return
  try{worker.execute{
   try{while(!stopping)processFrame(pending.getAndSet(null)?:break)}
   finally{busy.set(false);if(!stopping&&pending.get()!=null)processPending()}
  }}catch(_:RejectedExecutionException){busy.set(false);pending.set(null)}
 }
 private fun processFrame(frame:Frame){
  if(stopping||frame.epoch!=epoch.get())return
  if(!FrameOverlayAccessibilityService.isConnected()){
   lastError="Presentation service disconnected; capture stopped.";main.post{stopCapture(true)};return
  }
  val previous=synchronized(frameLock){
   if(stopping||frame.epoch!=epoch.get())return
   val old=oldPixels
   if(old!=null&&oldWidth==frame.width&&oldHeight==frame.height&&oldHash==frame.hash&&old.contentEquals(frame.pixels)){
    CaptureStats.onDuplicate();return
   }
   old?.let{Previous(it,oldWidth,oldHeight,oldTimestamp)}
  }
  CaptureStats.onUnique()
  if(previous!=null){
   val interval=frame.timestampNs-previous.timestampNs
   val schedule=FramePresentationScheduler.forPair(interval,displayIntervalNs())
   if(schedule!=null){
    val generated=engine.interpolate(previous.pixels,frame.pixels,frame.width,frame.height)
    engineStatus=(engine as? AutoFrameInterpolationEngine)?.status?:engine.displayName
    val elapsed=(System.nanoTime()-frame.timestampNs).coerceAtLeast(0L)/1_000_000L
    val lead=schedule.midpointDelayMs-elapsed
    if(generated!=null&&!stopping&&frame.epoch==epoch.get()&&lead>0&&generated.processingMillis<schedule.midpointDelayMs){
     CaptureStats.onGenerated(generated.processingMillis)
     main.post{
      if(stopping||frame.epoch!=epoch.get())return@post
      val now=(System.nanoTime()-frame.timestampNs).coerceAtLeast(0L)/1_000_000L
      val delay=schedule.midpointDelayMs-now
      if(delay<=0){CaptureStats.onDropped();cancelOutput();FrameOverlayAccessibilityService.hide();return@post}
      cancelOutput()
      val midpoint=Runnable{
       clearTask=null
       if(!stopping&&frame.epoch==epoch.get()){
        FrameOverlayAccessibilityService.present(generated.pixels,generated.width,generated.height)
        val clear=Runnable{clearTask=null;if(!stopping&&frame.epoch==epoch.get())FrameOverlayAccessibilityService.hide()}
        clearTask=clear;main.postDelayed(clear,schedule.holdDurationMs)
       }
      }
      clearTask=midpoint;main.postDelayed(midpoint,delay)
     }
    }else{CaptureStats.onDropped();main.post{cancelOutput();FrameOverlayAccessibilityService.hide()}}
   }else main.post{cancelOutput();FrameOverlayAccessibilityService.hide()}
  }else main.post{cancelOutput();FrameOverlayAccessibilityService.hide()}
  synchronized(frameLock){
   if(!stopping&&frame.epoch==epoch.get()){
    oldPixels=frame.pixels;oldWidth=frame.width;oldHeight=frame.height;oldTimestamp=frame.timestampNs;oldHash=frame.hash
   }
  }
 }
 private fun displayIntervalNs():Long{
  val hz=runCatching{@Suppress("DEPRECATION") getSystemService(WindowManager::class.java).defaultDisplay.refreshRate}.getOrDefault(60f).coerceIn(30f,240f)
  return (1_000_000_000.0/hz).toLong()
 }
 private fun cancelOutput(){clearTask?.let(main::removeCallbacks);clearTask=null}
 private fun createChannel(){
  val channel=NotificationChannel(CHANNEL_ID,"Frame generation",NotificationManager.IMPORTANCE_LOW)
  channel.description="Active capture and frame generation session"
  getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
 }
 private fun buildNotification():Notification{
  val stop=PendingIntent.getService(this,4102,Intent(this,FrameCaptureService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val open=PendingIntent.getActivity(this,4103,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  return Notification.Builder(this,CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_camera)
   .setContentTitle("FrameGen Universal is active").setContentText("Processing selected app window")
   .setContentIntent(open).setOngoing(true)
   .addAction(Notification.Action.Builder(Icon.createWithResource(this,android.R.drawable.ic_media_pause),"Stop",stop).build()).build()
 }
 private fun stopCapture(stopProjection:Boolean){
  if(stopping)return
  stopping=true;epoch.incrementAndGet();active=false;pending.set(null);synchronized(frameLock){clearPrevious()}
  main.post{cancelOutput();FrameOverlayAccessibilityService.remove()}
  runCatching{display?.release()};display=null
  runCatching{reader?.setOnImageAvailableListener(null,null)}
  runCatching{reader?.close()};reader=null
  val p=projection;val cb=projectionCallback;projection=null;projectionCallback=null
  if(p!=null&&cb!=null)runCatching{p.unregisterCallback(cb)}
  if(stopProjection)runCatching{p?.stop()}
  if(::engine.isInitialized){val closing=engine;runCatching{worker.execute{runCatching{closing.close()}}}}
  runCatching{stopForeground(STOP_FOREGROUND_REMOVE)};stopSelf()
 }
 override fun onDestroy(){if(!stopping)stopCapture(true);captureThread?.quitSafely();worker.shutdown();super.onDestroy()}
 companion object{
  const val ACTION_START="org.framegen.universal.capture.START";const val ACTION_STOP="org.framegen.universal.capture.STOP"
  const val EXTRA_RESULT_CODE="capture_result_code";const val EXTRA_RESULT_DATA="capture_result_data"
  private const val CHANNEL_ID="framegen_capture";private const val NOTIFICATION_ID=4101
  private const val MAX_SIDE=480;private const val MAX_PIXELS=4_194_304L
  @Volatile var active=false;private set
  @Volatile var lastError:String?=null
  @Volatile var engineStatus="Not started"
 }
}
