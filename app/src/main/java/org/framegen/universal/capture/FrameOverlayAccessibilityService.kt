package org.framegen.universal.capture
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView

/** Minimal trusted presenter. It ignores accessibility event data and cannot inject gestures. */
class FrameOverlayAccessibilityService:AccessibilityService(){
 private lateinit var wm:WindowManager
 private val main=Handler(Looper.getMainLooper())
 private var image:ImageView?=null
 private var bitmap:Bitmap?=null
 private var attached=false
 override fun onServiceConnected(){super.onServiceConnected();wm=getSystemService(WindowManager::class.java);instance=this}
 override fun onAccessibilityEvent(event:AccessibilityEvent?){ /* no event inspection */ }
 override fun onInterrupt(){hideOutput()}
 fun showOutput()=onMain{
  if(attached)return@onMain
  val view=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_XY;setBackgroundColor(android.graphics.Color.TRANSPARENT);visibility=View.INVISIBLE;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;contentDescription="FrameGen generated frame"}
  val lp=WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
   WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START}
  try{wm.addView(view,lp);image=view;attached=true}catch(_:RuntimeException){attached=false}
 }
 fun present(pixels:IntArray,width:Int,height:Int){
  val count=width.toLong()*height
  if(width<=0||height<=0||count>4_194_304L||count!=pixels.size.toLong())return
  val copy=pixels.copyOf()
  onMain{
   if(!attached)showOutput()
   val view=image?:return@onMain
   var target=bitmap
   if(target==null||target.width!=width||target.height!=height||target.isRecycled){target?.recycle();target=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap=target;view.setImageBitmap(target)}
   target!!.setPixels(copy,0,width,0,0,width,height);view.visibility=View.VISIBLE;view.invalidate()
  }
 }
 fun hideOutput()=onMain{image?.visibility=View.INVISIBLE}
 fun removeOutput()=onMain{image?.let{if(attached)runCatching{wm.removeView(it)};it.setImageDrawable(null)};attached=false;image=null;bitmap?.let{if(!it.isRecycled)it.recycle()};bitmap=null}
 private fun onMain(block:()->Unit){if(Looper.myLooper()==Looper.getMainLooper())block()else main.post(block)}
 override fun onUnbind(intent:Intent?):Boolean{removeOutput();if(instance===this)instance=null;return super.onUnbind(intent)}
 override fun onDestroy(){removeOutput();if(instance===this)instance=null;super.onDestroy()}
 companion object{
  @Volatile private var instance:FrameOverlayAccessibilityService?=null
  fun isConnected()=instance!=null
  fun isEnabled(context:Context):Boolean{val expected=ComponentName(context,FrameOverlayAccessibilityService::class.java);val raw=Settings.Secure.getString(context.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?:return false;return raw.split(':').any{ComponentName.unflattenFromString(it)==expected}}
  fun show()=instance?.showOutput()
  fun present(pixels:IntArray,width:Int,height:Int)=instance?.present(pixels,width,height)
  fun hide()=instance?.hideOutput()
  fun remove()=instance?.removeOutput()
 }
}
