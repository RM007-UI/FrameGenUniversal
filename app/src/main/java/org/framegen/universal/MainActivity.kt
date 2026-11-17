package org.framegen.universal
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.framegen.universal.capture.CaptureStats
import org.framegen.universal.capture.FrameCaptureService
import org.framegen.universal.capture.FrameOverlayAccessibilityService

private val Ink=Color(0xFF090E1A);private val Panel=Color(0xFF151E30)
private val Accent=Color(0xFF76E6D5);private val Muted=Color(0xFF9BAAC1)

class MainActivity:ComponentActivity(){
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{
  MaterialTheme(colorScheme=darkColorScheme(primary=Accent,background=Ink,surface=Panel,onPrimary=Ink,onBackground=Color.White,onSurface=Color.White)){
   Surface(Modifier.fillMaxSize(),color=Ink){Dashboard()}
  }
 }}
}
@Composable private fun Dashboard(){
 val context=LocalContext.current
 var status by remember{mutableStateOf("آمادهٔ شروع")}
 var report by remember{mutableStateOf("در حال بررسی Vulkan…")}
 var stats by remember{mutableStateOf(CaptureStats.snapshot())}
 var running by remember{mutableStateOf(FrameCaptureService.active)}
 var serviceOn by remember{mutableStateOf(FrameOverlayAccessibilityService.isConnected())}
 var serviceEnabled by remember{mutableStateOf(FrameOverlayAccessibilityService.isEnabled(context))}
 var engine by remember{mutableStateOf(FrameCaptureService.engineStatus)}
 val captureLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
  if(result.resultCode==Activity.RESULT_OK&&result.data!=null){
   val command=Intent(context,FrameCaptureService::class.java).apply{
    action=FrameCaptureService.ACTION_START
    putExtra(FrameCaptureService.EXTRA_RESULT_CODE,result.resultCode)
    putExtra(FrameCaptureService.EXTRA_RESULT_DATA,result.data)
   }
   runCatching{context.startForegroundService(command)}
    .onSuccess{status="مجوز ضبط صادر شد؛ پنجرهٔ بازی را انتخاب کرده‌ای."}
    .onFailure{status="شروع سرویس ناموفق بود: "+(it.message?:it.javaClass.simpleName)}
  }else status="درخواست ضبط صفحه لغو شد."
 }
 val startConsent={
  status="در پنجرهٔ اندروید، پنجرهٔ بازی را انتخاب کن، نه کل صفحه."
  val manager=context.getSystemService(MediaProjectionManager::class.java)
  captureLauncher.launch(manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice()))
 }
 val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
  if(!granted)status="اعلان‌ها غیرفعال‌اند؛ برای توقف از داخل برنامه استفاده کن."
  startConsent()
 }
 LaunchedEffect(Unit){
  report=withContext(Dispatchers.IO){runCatching{NativeProbe.inspectVulkan()}.getOrElse{"Vulkan probe: "+(it.message?:it.javaClass.simpleName)}}
  while(true){
   running=FrameCaptureService.active;serviceOn=FrameOverlayAccessibilityService.isConnected()
   serviceEnabled=FrameOverlayAccessibilityService.isEnabled(context);stats=CaptureStats.snapshot()
   engine=FrameCaptureService.engineStatus;FrameCaptureService.lastError?.let{status=it};delay(1000)
  }
 }
 Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
  Text("FRAMEGEN",color=Accent,fontSize=12.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp)
  Text("Universal",color=Color.White,fontSize=34.sp,fontWeight=FontWeight.Bold)
  Text("Android game frame interpolation",color=Muted,fontSize=13.sp)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Pill(if(running)"CAPTURING" else "GPU PREVIEW",if(running)Accent else Muted);Pill("v0.4.0",Muted)}
  CardBox("آماده‌سازی لایهٔ نمایش","سرویس دسترس‌پذیری فقط پنجرهٔ خروجی را ایجاد می‌کند"){
   Text(when{serviceOn->"سرویس نمایش متصل است.";serviceEnabled->"فعال شده؛ اتصال سیستم هنوز برقرار نیست.";else->"برای عبور لمس بازی، سرویس FrameGen را فعال کن. این سرویس محتوای صفحه را نمی‌خواند و gesture اجرا نمی‌کند."},color=if(serviceOn)Accent else Color.White,fontSize=12.sp)
   OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))},enabled=!running,modifier=Modifier.fillMaxWidth()){Text("باز کردن تنظیمات دسترس‌پذیری")}
  }
  CardBox("جلسهٔ ضبط","MediaProjection · مجوز جداگانه برای هر نشست"){
   Text(status,color=Color.White,fontSize=12.sp)
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()){
    Button(onClick={
     when{
      !serviceOn->{status="ابتدا سرویس FrameGen را فعال کن.";context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}
      Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED->notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
      else->startConsent()
     }
    },enabled=!running,modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=Accent,contentColor=Ink)){Text("شروع تولید فریم",fontWeight=FontWeight.Bold)}
    OutlinedButton(onClick={context.startService(Intent(context,FrameCaptureService::class.java).setAction(FrameCaptureService.ACTION_STOP));status="درخواست توقف ارسال شد."},enabled=running){Text("توقف")}
   }
  }
  CardBox("موتور فعال","انتخاب خودکار Vulkan و بازگشت به CPU"){
   Text(engine,color=Accent,fontSize=12.sp)
   Text("موتور Vulkan این نسخه مستقل است؛ پورت مستقیم Win-FG یا WFG نیست.",color=Muted,fontSize=11.sp)
  }
  CardBox("آمار زنده","فریم‌های واقعی و تولیدشده جداگانه محاسبه می‌شوند"){
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()){
    Metric("Source FPS",stats.sourceFps.toInt().toString(),Modifier.weight(1f))
    Metric("Generated",stats.generatedFps.toInt().toString(),Modifier.weight(1f))
    Metric("Average",stats.averageProcessingMs.toInt().toString()+" ms",Modifier.weight(1f))
   }
   Text("دریافتی "+stats.capturedFrames+" · یکتا "+stats.uniqueFrames+" · تکراری "+stats.duplicateFrames+" · ازدست‌رفته "+stats.droppedFrames,color=Muted,fontSize=10.sp)
  }
  CardBox("گزارش سخت‌افزار","Vulkan capability probe"){Text(report,color=Color.White,fontSize=10.sp,lineHeight=15.sp)}
  CardBox("محدودیت‌ها","نسخهٔ آزمایشی؛ نیازمند تست گوشی"){
   Bullet("Android 14 یا جدیدتر لازم است.")
   Bullet("ضلع بلند تصویر به 480 پیکسل محدود شده است.")
   Bullet("در انتخابگر اندروید، پنجرهٔ خود بازی را انتخاب کن.")
   Bullet("مسیر GPU از upload/readback استفاده می‌کند و داخل swapchain بازی نیست.")
   Bullet("بعضی بازی‌ها ضبط یا لایهٔ شناور را مسدود می‌کنند؛ ضدتقلب دور زده نمی‌شود.")
   Bullet("نمایشگر 60Hz نمی‌تواند 120 فریم مستقل نمایش دهد.")
  }
  Text("بدون DLL، مدل خارجی یا مجوز اینترنت",color=Muted,fontSize=10.sp)
 }
}
@Composable private fun CardBox(title:String,subtitle:String,content:@Composable ColumnScope.()->Unit){
 Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(18.dp)){
  Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){Text(title,color=Color.White,fontSize=15.sp,fontWeight=FontWeight.SemiBold);Text(subtitle,color=Muted,fontSize=11.sp);content()}
 }
}
@Composable private fun Pill(value:String,tint:Color){
 Box(Modifier.background(Color(0xFF202C43),RoundedCornerShape(50.dp)).padding(horizontal=10.dp,vertical=6.dp)){Text(value,color=tint,fontSize=10.sp,fontWeight=FontWeight.Bold)}
}
@Composable private fun Metric(label:String,value:String,modifier:Modifier=Modifier){
 Column(modifier.background(Color(0xFF202C43),RoundedCornerShape(12.dp)).padding(10.dp)){Text(value,color=Accent,fontSize=19.sp,fontWeight=FontWeight.Bold);Text(label,color=Muted,fontSize=10.sp)}
}
@Composable private fun Bullet(value:String){
 Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(8.dp)){Text("•",color=Accent);Text(value,color=Muted,fontSize=11.sp,lineHeight=16.sp)}
}
