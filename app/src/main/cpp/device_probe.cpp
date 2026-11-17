#include <jni.h>
#include <vulkan/vulkan.h>
#include <algorithm>
#include <sstream>
#include <string>
#include <vector>
namespace {
std::string inspect(){
 std::ostringstream out;uint32_t loader=VK_API_VERSION_1_0;vkEnumerateInstanceVersion(&loader);
 out<<"Vulkan loader "<<VK_VERSION_MAJOR(loader)<<"."<<VK_VERSION_MINOR(loader)<<"."<<VK_VERSION_PATCH(loader)<<"\n";
 VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};app.pApplicationName="FrameGen Probe";app.apiVersion=std::min(loader,static_cast<uint32_t>(VK_API_VERSION_1_1));
 VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ci.pApplicationInfo=&app;VkInstance instance=VK_NULL_HANDLE;
 if(vkCreateInstance(&ci,nullptr,&instance)!=VK_SUCCESS){out<<"vkCreateInstance failed\n";return out.str();}
 uint32_t count=0;
 if(vkEnumeratePhysicalDevices(instance,&count,nullptr)!=VK_SUCCESS||count==0){out<<"No Vulkan physical devices\n";vkDestroyInstance(instance,nullptr);return out.str();}
 std::vector<VkPhysicalDevice> devices(count);vkEnumeratePhysicalDevices(instance,&count,devices.data());
 for(uint32_t i=0;i<count;i++){
  VkPhysicalDeviceProperties props{};vkGetPhysicalDeviceProperties(devices[i],&props);
  uint32_t qn=0;vkGetPhysicalDeviceQueueFamilyProperties(devices[i],&qn,nullptr);
  std::vector<VkQueueFamilyProperties> queues(qn);vkGetPhysicalDeviceQueueFamilyProperties(devices[i],&qn,queues.data());
  bool compute=false;for(const auto& q:queues)compute|=q.queueCount>0&&(q.queueFlags&VK_QUEUE_COMPUTE_BIT);
  out<<"\nGPU "<<(i+1)<<": "<<props.deviceName<<"\n";
  out<<"API "<<VK_VERSION_MAJOR(props.apiVersion)<<"."<<VK_VERSION_MINOR(props.apiVersion)<<"."<<VK_VERSION_PATCH(props.apiVersion)<<"\n";
  out<<"Driver "<<props.driverVersion<<"\nCompute queue: "<<(compute?"yes":"no")<<"\n";
  out<<"Max storage buffer: "<<props.limits.maxStorageBufferRange<<" bytes\n";
  out<<"8x8 workgroup: "<<((props.limits.maxComputeWorkGroupSize[0]>=8&&props.limits.maxComputeWorkGroupSize[1]>=8&&props.limits.maxComputeWorkGroupInvocations>=64)?"yes":"no")<<"\n";
  bool candidate=props.apiVersion>=VK_API_VERSION_1_1&&compute&&props.limits.maxStorageBufferRange>=4u*1024u*1024u*4u;
  out<<"Backend candidate: "<<(candidate?"yes (runtime init still required)":"no")<<"\n";
 }
 vkDestroyInstance(instance,nullptr);out<<"\nCapability probe only; not a performance guarantee.\n";return out.str();
}
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_framegen_universal_NativeProbe_inspectVulkan(JNIEnv* env,jobject){auto value=inspect();return env->NewStringUTF(value.c_str());}
