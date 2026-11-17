#include <jni.h>
#include <vulkan/vulkan.h>
#include <android/log.h>
#include "framegen_shader.hpp"
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define FG_LOGE(...) __android_log_print(ANDROID_LOG_ERROR,"FrameGenGPU",__VA_ARGS__)
namespace {
constexpr uint64_t MAX_PIXELS=4ull*1024ull*1024ull;
struct Buffer{VkBuffer handle=VK_NULL_HANDLE;VkDeviceMemory memory=VK_NULL_HANDLE;void* mapped=nullptr;bool coherent=false;};

class VulkanEngine{
public:
 ~VulkanEngine(){shutdown();}
 bool initialize(std::string& message){
  std::lock_guard<std::mutex> guard(lock_);
  if(ready_){message="GPU ready: "+deviceName_;return true;}
  if(!createInstance())return fail(message,"vkCreateInstance failed");
  if(!selectDevice())return fail(message,"No compatible Vulkan compute device");
  if(!createDevice())return fail(message,"vkCreateDevice failed");
  if(!createCommandResources())return fail(message,"Vulkan command resources failed");
  if(!createPipeline())return fail(message,"Vulkan compute pipeline failed");
  ready_=true;message="GPU ready: "+deviceName_;return true;
 }
 bool interpolate(const jint* previous,const jint* current,uint32_t width,uint32_t height,
                  std::vector<jint>& result,std::string& error){
  std::lock_guard<std::mutex> guard(lock_);
  uint64_t count=static_cast<uint64_t>(width)*height;
  if(!ready_){error=lastError_.empty()?"Vulkan engine is not initialized":lastError_;return false;}
  if(!previous||!current||width<32||height<32||count>MAX_PIXELS||count>UINT32_MAX){
   error="Invalid or oversized frame";return false;
  }
  if((width+7)/8>limits_.maxComputeWorkGroupCount[0]||(height+7)/8>limits_.maxComputeWorkGroupCount[1]){
   error="Frame dimensions exceed compute dispatch limits";return false;
  }
  if(!ensureBuffers(width,height)){error="Vulkan storage-buffer allocation failed";return false;}
  VkDeviceSize bytes=static_cast<VkDeviceSize>(count)*sizeof(uint32_t);
  std::memcpy(previous_.mapped,previous,static_cast<size_t>(bytes));
  std::memcpy(current_.mapped,current,static_cast<size_t>(bytes));
  if(!flush(previous_)||!flush(current_)){error="Could not flush Vulkan input buffers";return false;}

  if(vkResetCommandBuffer(commandBuffer_,0)!=VK_SUCCESS){error="vkResetCommandBuffer failed";return false;}
  VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
  begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
  if(vkBeginCommandBuffer(commandBuffer_,&begin)!=VK_SUCCESS){error="vkBeginCommandBuffer failed";return false;}
  bufferBarrier(previous_.handle,VK_ACCESS_HOST_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
  bufferBarrier(current_.handle,VK_ACCESS_HOST_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
  // Order the host read of the previous output before this dispatch overwrites it.
  bufferBarrier(output_.handle,VK_ACCESS_HOST_READ_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
  vkCmdBindPipeline(commandBuffer_,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline_);
  vkCmdBindDescriptorSets(commandBuffer_,VK_PIPELINE_BIND_POINT_COMPUTE,pipelineLayout_,0,1,&descriptorSet_,0,nullptr);
  const int32_t dimensions[2]={static_cast<int32_t>(width),static_cast<int32_t>(height)};
  vkCmdPushConstants(commandBuffer_,pipelineLayout_,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(dimensions),dimensions);
  vkCmdDispatch(commandBuffer_,(width+7)/8,(height+7)/8,1);
  bufferBarrier(output_.handle,VK_ACCESS_SHADER_WRITE_BIT,VK_ACCESS_HOST_READ_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT);
  if(vkEndCommandBuffer(commandBuffer_)!=VK_SUCCESS){error="vkEndCommandBuffer failed";return false;}
  if(vkResetFences(device_,1,&fence_)!=VK_SUCCESS){error="vkResetFences failed";return false;}
  VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};submit.commandBufferCount=1;submit.pCommandBuffers=&commandBuffer_;
  if(vkQueueSubmit(queue_,1,&submit,fence_)!=VK_SUCCESS){error="vkQueueSubmit failed";return false;}
  if(vkWaitForFences(device_,1,&fence_,VK_TRUE,1'000'000'000ull)!=VK_SUCCESS){error="Vulkan compute timed out";return false;}
  if(!invalidate(output_)){error="Could not invalidate Vulkan output";return false;}
  result.resize(static_cast<size_t>(count));
  std::memcpy(result.data(),output_.mapped,static_cast<size_t>(bytes));
  lastError_.clear();return true;
 }
 std::string lastError(){std::lock_guard<std::mutex> guard(lock_);return lastError_;}
 void setLastError(const std::string& message){std::lock_guard<std::mutex> guard(lock_);lastError_=message;}
 void shutdown(){
  std::lock_guard<std::mutex> guard(lock_);
  cleanup();lastError_.clear();
 }
private:
 bool fail(std::string& message,const char* why){message=why;lastError_=why;cleanup();return false;}
 bool createInstance(){
  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
  app.pApplicationName="FrameGen Universal";app.applicationVersion=1;app.pEngineName="FrameGen Compute";app.engineVersion=1;app.apiVersion=VK_API_VERSION_1_1;
  VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ci.pApplicationInfo=&app;
  return vkCreateInstance(&ci,nullptr,&instance_)==VK_SUCCESS;
 }
 bool selectDevice(){
  uint32_t count=0;if(vkEnumeratePhysicalDevices(instance_,&count,nullptr)!=VK_SUCCESS||count==0)return false;
  std::vector<VkPhysicalDevice> devices(count);if(vkEnumeratePhysicalDevices(instance_,&count,devices.data())!=VK_SUCCESS)return false;
  int best=-1;
  for(auto candidate:devices){
   VkPhysicalDeviceProperties props{};vkGetPhysicalDeviceProperties(candidate,&props);
   if(props.apiVersion<VK_API_VERSION_1_1||props.limits.maxStorageBufferRange<MAX_PIXELS*4||
      props.limits.maxComputeWorkGroupInvocations<64||props.limits.maxComputeWorkGroupSize[0]<8||props.limits.maxComputeWorkGroupSize[1]<8)continue;
   uint32_t familyCount=0;vkGetPhysicalDeviceQueueFamilyProperties(candidate,&familyCount,nullptr);
   std::vector<VkQueueFamilyProperties> families(familyCount);vkGetPhysicalDeviceQueueFamilyProperties(candidate,&familyCount,families.data());
   for(uint32_t i=0;i<familyCount;i++){
    if(families[i].queueCount==0||(families[i].queueFlags&VK_QUEUE_COMPUTE_BIT)==0)continue;
    int score=(families[i].queueFlags&VK_QUEUE_GRAPHICS_BIT)?2:0;
    if(props.deviceType==VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU)score+=2;
    if(score>best){best=score;physical_=candidate;queueFamily_=i;deviceName_=props.deviceName;limits_=props.limits;}
   }
  }
  return physical_!=VK_NULL_HANDLE;
 }
 bool createDevice(){
  float priority=1.0f;VkDeviceQueueCreateInfo q{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
  q.queueFamilyIndex=queueFamily_;q.queueCount=1;q.pQueuePriorities=&priority;
  VkDeviceCreateInfo ci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};ci.queueCreateInfoCount=1;ci.pQueueCreateInfos=&q;
  if(vkCreateDevice(physical_,&ci,nullptr,&device_)!=VK_SUCCESS)return false;
  vkGetDeviceQueue(device_,queueFamily_,0,&queue_);vkGetPhysicalDeviceMemoryProperties(physical_,&memory_);
  return queue_!=VK_NULL_HANDLE;
 }
 bool createCommandResources(){
  VkCommandPoolCreateInfo pool{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};pool.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;pool.queueFamilyIndex=queueFamily_;
  if(vkCreateCommandPool(device_,&pool,nullptr,&commandPool_)!=VK_SUCCESS)return false;
  VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ai.commandPool=commandPool_;ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ai.commandBufferCount=1;
  if(vkAllocateCommandBuffers(device_,&ai,&commandBuffer_)!=VK_SUCCESS)return false;
  VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};return vkCreateFence(device_,&fi,nullptr,&fence_)==VK_SUCCESS;
 }
 bool createPipeline(){
  VkDescriptorSetLayoutBinding bindings[3]{};
  for(uint32_t i=0;i<3;i++){bindings[i].binding=i;bindings[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;bindings[i].descriptorCount=1;bindings[i].stageFlags=VK_SHADER_STAGE_COMPUTE_BIT;}
  VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};li.bindingCount=3;li.pBindings=bindings;
  if(vkCreateDescriptorSetLayout(device_,&li,nullptr,&setLayout_)!=VK_SUCCESS)return false;
  VkPushConstantRange push{};push.stageFlags=VK_SHADER_STAGE_COMPUTE_BIT;push.offset=0;push.size=sizeof(int32_t)*2;
  VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};pli.setLayoutCount=1;pli.pSetLayouts=&setLayout_;pli.pushConstantRangeCount=1;pli.pPushConstantRanges=&push;
  if(vkCreatePipelineLayout(device_,&pli,nullptr,&pipelineLayout_)!=VK_SUCCESS)return false;
  VkShaderModuleCreateInfo si{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};si.codeSize=framegen::MOTION_MIDPOINT_WORDS*sizeof(uint32_t);si.pCode=framegen::MOTION_MIDPOINT;
  if(vkCreateShaderModule(device_,&si,nullptr,&shader_)!=VK_SUCCESS)return false;
  VkPipelineShaderStageCreateInfo stage{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};stage.stage=VK_SHADER_STAGE_COMPUTE_BIT;stage.module=shader_;stage.pName="main";
  VkComputePipelineCreateInfo pi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};pi.stage=stage;pi.layout=pipelineLayout_;
  if(vkCreateComputePipelines(device_,VK_NULL_HANDLE,1,&pi,nullptr,&pipeline_)!=VK_SUCCESS)return false;
  return true;
 }
 uint32_t findMemory(uint32_t bits){
  uint32_t fallback=UINT32_MAX;
  for(uint32_t i=0;i<memory_.memoryTypeCount;i++){
   if((bits&(1u<<i))==0)continue;
   auto flags=memory_.memoryTypes[i].propertyFlags;
   if((flags&VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)==0)continue;
   if((flags&VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)!=0)return i;
   if(fallback==UINT32_MAX)fallback=i;
  }
  return fallback;
 }
 bool createBuffer(VkDeviceSize bytes,Buffer& out){
  VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};bi.size=bytes;bi.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;bi.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
  if(vkCreateBuffer(device_,&bi,nullptr,&out.handle)!=VK_SUCCESS)return false;
  VkMemoryRequirements req{};vkGetBufferMemoryRequirements(device_,out.handle,&req);
  uint32_t type=findMemory(req.memoryTypeBits);if(type==UINT32_MAX)return false;
  out.coherent=(memory_.memoryTypes[type].propertyFlags&VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)!=0;
  VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ai.allocationSize=req.size;ai.memoryTypeIndex=type;
  if(vkAllocateMemory(device_,&ai,nullptr,&out.memory)!=VK_SUCCESS)return false;
  if(vkBindBufferMemory(device_,out.handle,out.memory,0)!=VK_SUCCESS)return false;
  return vkMapMemory(device_,out.memory,0,req.size,0,&out.mapped)==VK_SUCCESS;
 }
 void destroyBuffer(Buffer& b){
  if(device_){if(b.mapped&&b.memory)vkUnmapMemory(device_,b.memory);if(b.handle)vkDestroyBuffer(device_,b.handle,nullptr);if(b.memory)vkFreeMemory(device_,b.memory,nullptr);}
  b=Buffer{};
 }
 void destroySizeResources(){
  if(!device_)return;
  if(descriptorPool_)vkDestroyDescriptorPool(device_,descriptorPool_,nullptr);
  descriptorPool_=VK_NULL_HANDLE;descriptorSet_=VK_NULL_HANDLE;
  destroyBuffer(previous_);destroyBuffer(current_);destroyBuffer(output_);
  width_=height_=0;
 }
 bool ensureBuffers(uint32_t width,uint32_t height){
  if(width==width_&&height==height_&&previous_.handle)return true;
  destroySizeResources();width_=width;height_=height;
  VkDeviceSize bytes=static_cast<VkDeviceSize>(width)*height*sizeof(uint32_t);
  if(!createBuffer(bytes,previous_)||!createBuffer(bytes,current_)||!createBuffer(bytes,output_))return false;
  VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,3};VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
  pool.maxSets=1;pool.poolSizeCount=1;pool.pPoolSizes=&size;
  if(vkCreateDescriptorPool(device_,&pool,nullptr,&descriptorPool_)!=VK_SUCCESS)return false;
  VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};ai.descriptorPool=descriptorPool_;ai.descriptorSetCount=1;ai.pSetLayouts=&setLayout_;
  if(vkAllocateDescriptorSets(device_,&ai,&descriptorSet_)!=VK_SUCCESS)return false;
  VkDescriptorBufferInfo infos[3]={{previous_.handle,0,bytes},{current_.handle,0,bytes},{output_.handle,0,bytes}};
  VkWriteDescriptorSet writes[3]{};
  for(uint32_t i=0;i<3;i++){writes[i].sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;writes[i].dstSet=descriptorSet_;writes[i].dstBinding=i;writes[i].descriptorCount=1;writes[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;writes[i].pBufferInfo=&infos[i];}
  vkUpdateDescriptorSets(device_,3,writes,0,nullptr);return true;
 }
 bool flush(const Buffer& b){if(b.coherent)return true;VkMappedMemoryRange r{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};r.memory=b.memory;r.offset=0;r.size=VK_WHOLE_SIZE;return vkFlushMappedMemoryRanges(device_,1,&r)==VK_SUCCESS;}
 bool invalidate(const Buffer& b){if(b.coherent)return true;VkMappedMemoryRange r{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};r.memory=b.memory;r.offset=0;r.size=VK_WHOLE_SIZE;return vkInvalidateMappedMemoryRanges(device_,1,&r)==VK_SUCCESS;}
 void bufferBarrier(VkBuffer buffer,VkAccessFlags src,VkAccessFlags dst,VkPipelineStageFlags srcStage,VkPipelineStageFlags dstStage){
  VkBufferMemoryBarrier b{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};b.srcAccessMask=src;b.dstAccessMask=dst;b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;b.buffer=buffer;b.offset=0;b.size=VK_WHOLE_SIZE;
  vkCmdPipelineBarrier(commandBuffer_,srcStage,dstStage,0,0,nullptr,1,&b,0,nullptr);
 }
 void cleanup(){
  if(device_)vkDeviceWaitIdle(device_);destroySizeResources();
  if(device_){
   if(pipeline_)vkDestroyPipeline(device_,pipeline_,nullptr);
   if(shader_)vkDestroyShaderModule(device_,shader_,nullptr);
   if(pipelineLayout_)vkDestroyPipelineLayout(device_,pipelineLayout_,nullptr);
   if(setLayout_)vkDestroyDescriptorSetLayout(device_,setLayout_,nullptr);
   if(fence_)vkDestroyFence(device_,fence_,nullptr);
   if(commandPool_)vkDestroyCommandPool(device_,commandPool_,nullptr);
   vkDestroyDevice(device_,nullptr);
  }
  if(instance_)vkDestroyInstance(instance_,nullptr);
  instance_=VK_NULL_HANDLE;physical_=VK_NULL_HANDLE;device_=VK_NULL_HANDLE;queue_=VK_NULL_HANDLE;
  commandPool_=VK_NULL_HANDLE;commandBuffer_=VK_NULL_HANDLE;fence_=VK_NULL_HANDLE;
  setLayout_=VK_NULL_HANDLE;pipelineLayout_=VK_NULL_HANDLE;shader_=VK_NULL_HANDLE;pipeline_=VK_NULL_HANDLE;
  descriptorPool_=VK_NULL_HANDLE;descriptorSet_=VK_NULL_HANDLE;previous_=Buffer{};current_=Buffer{};output_=Buffer{};
  queueFamily_=width_=height_=0;ready_=false;deviceName_.clear();
 }
 std::mutex lock_;bool ready_=false;std::string lastError_,deviceName_;
 VkInstance instance_=VK_NULL_HANDLE;VkPhysicalDevice physical_=VK_NULL_HANDLE;VkPhysicalDeviceLimits limits_{};
 uint32_t queueFamily_=0;VkPhysicalDeviceMemoryProperties memory_{};VkDevice device_=VK_NULL_HANDLE;VkQueue queue_=VK_NULL_HANDLE;
 VkCommandPool commandPool_=VK_NULL_HANDLE;VkCommandBuffer commandBuffer_=VK_NULL_HANDLE;VkFence fence_=VK_NULL_HANDLE;
 VkDescriptorSetLayout setLayout_=VK_NULL_HANDLE;VkPipelineLayout pipelineLayout_=VK_NULL_HANDLE;VkShaderModule shader_=VK_NULL_HANDLE;
 VkPipeline pipeline_=VK_NULL_HANDLE;VkDescriptorPool descriptorPool_=VK_NULL_HANDLE;VkDescriptorSet descriptorSet_=VK_NULL_HANDLE;
 Buffer previous_,current_,output_;uint32_t width_=0,height_=0;
};
VulkanEngine engine;
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_framegen_universal_engine_NativeGpuEngine_nativeInitialize(JNIEnv* env,jobject){
 std::string message;engine.initialize(message);return env->NewStringUTF(message.c_str());
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_framegen_universal_engine_NativeGpuEngine_nativeLastError(JNIEnv* env,jobject){
 auto message=engine.lastError();return env->NewStringUTF(message.c_str());
}
extern "C" JNIEXPORT jintArray JNICALL
Java_org_framegen_universal_engine_NativeGpuEngine_nativeInterpolate(JNIEnv* env,jobject,jintArray a,jintArray b,jint width,jint height){
 if(!a||!b||width<=0||height<=0)return nullptr;
 jsize n=env->GetArrayLength(a);
 if(n!=env->GetArrayLength(b)||static_cast<uint64_t>(width)*height!=static_cast<uint64_t>(n)||static_cast<uint64_t>(n)>MAX_PIXELS)return nullptr;
 std::vector<jint> previous(static_cast<size_t>(n)),current(static_cast<size_t>(n)),result;
 env->GetIntArrayRegion(a,0,n,previous.data());if(env->ExceptionCheck())return nullptr;
 env->GetIntArrayRegion(b,0,n,current.data());if(env->ExceptionCheck())return nullptr;
 std::string error;
 if(!engine.interpolate(previous.data(),current.data(),static_cast<uint32_t>(width),static_cast<uint32_t>(height),result,error)){
  engine.setLastError(error);FG_LOGE("GPU interpolation failed: %s",error.c_str());return nullptr;
 }
 jintArray out=env->NewIntArray(n);if(!out)return nullptr;
 env->SetIntArrayRegion(out,0,n,result.data());return env->ExceptionCheck()?nullptr:out;
}
extern "C" JNIEXPORT void JNICALL
Java_org_framegen_universal_engine_NativeGpuEngine_nativeShutdown(JNIEnv*,jobject){engine.shutdown();}
