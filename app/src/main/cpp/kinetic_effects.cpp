#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace {
constexpr float kPi = 3.14159265358979323846f;
struct Key { float t, v; int type; float x1, y1, x2, y2; };
struct Parameter { int effect; std::string name; float base; std::vector<Key> keys; };
struct Definition {
    const char* name; int id; const char* keys[7];
    float defaults[7], minimums[7], maximums[7]; int count;
};
static const Definition kDefinitions[] = {
    {"Motion Tile",1,{"Tile Width","Tile Height","Output Width","Output Height","Mirror Edges","Phase X","Phase Y"},{100,100,200,200,1,0,0},{10,10,100,100,0,-2,-2},{300,300,500,500,1,2,2},7},
    {"Wave Warp",2,{"Wave Height","Wave Width","Wave Speed","Wave Type","Direction","Phase","Pinning"},{0.012f,4,1,0,0,0,0},{0,1,-8,0,0,0,0},{0.08f,24,8,3,360,360,3},7},
    {"Warp Shake",3,{"X Amount","Y Amount","Frequency","Motion","Seed","Direction"},{0.008f,0.008f,8,0,0,0},{0,0,0.1f,0,0,0},{0.08f,0.08f,30,1,100,360},6},
    {"RGB Split",4,{"Red Offset","Green Offset","Blue Offset","Angle","Radial Split","Center X","Center Y"},{0.006f,0,-0.006f,0,0,0.5f,0.5f},{-0.04f,-0.04f,-0.04f,0,0,0,0},{0.04f,0.04f,0.04f,360,1,1,1},7},
    {"Oscillate",5,{"X Amplitude","Y Amplitude","Frequency","Wave Shape","Phase","Half Range"},{0.02f,0.02f,1,0,0,0},{0,0,0.1f,0,0,0},{0.15f,0.15f,12,3,360,1},6},
    {"Deep Glow",6,{"Threshold","Radius","Intensity","Quality","Spread","Saturation","Glow Only"},{0.65f,0.012f,1.2f,0,1,1,0},{0,0.001f,0,0,0.25f,0,0},{1,0.08f,4,1,3,2,1},7},
    {"Exposure And Gamma",7,{"Exposure","Gamma","Offset","Channel"},{0,1,0,0},{-5,0.1f,-1,0},{5,3,1,3},4}
};
const Definition* findDefinition(const std::string& name) {
    for (const auto& definition : kDefinitions) if (name == definition.name) return &definition;
    return nullptr;
}
float cubic(float t, float a, float b) {
    const float inv = 1.0f - t;
    return 3.0f*inv*inv*t*a + 3.0f*inv*t*t*b + t*t*t;
}
float bezier(float u, float x1, float y1, float x2, float y2) {
    float low=0.0f, high=1.0f;
    for(int i=0;i<20;++i) {
        const float mid=(low+high)*0.5f;
        if(cubic(mid,x1,x2)<u) low=mid; else high=mid;
    }
    return cubic((low+high)*0.5f,y1,y2);
}
float evaluateKeys(const Parameter& parameter, float time, float fallback) {
    if(parameter.keys.empty() || !std::isfinite(time)) return parameter.base;
    const auto& keys=parameter.keys;
    if(time<=keys.front().t) return keys.front().v;
    if(time>=keys.back().t) return keys.back().v;
    std::size_t i=0;
    while(i+1<keys.size()-1 && keys[i+1].t<=time) ++i;
    const Key& a=keys[i]; const Key& b=keys[i+1];
    const float span=b.t-a.t;
    if(!std::isfinite(span)||span<=0.000001f) return b.v;
    const float u=std::clamp((time-a.t)/span,0.0f,1.0f);
    float p=u;
    if(a.type==1) p=0.0f;
    else if(a.type==2) p=bezier(u,a.x1,a.y1,a.x2,a.y2);
    else if(a.type==3 && u>0.0f && u<1.0f)
        p=std::pow(2.0f,-10.0f*u)*std::sin((u*10.0f-0.75f)*(2.0f*kPi/3.0f))+1.0f;
    else if(a.type==4) {
        if(u<1.0f/2.75f) p=7.5625f*u*u;
        else if(u<2.0f/2.75f) { float q=u-1.5f/2.75f; p=7.5625f*q*q+0.75f; }
        else if(u<2.5f/2.75f) { float q=u-2.25f/2.75f; p=7.5625f*q*q+0.9375f; }
        else { float q=u-2.625f/2.75f; p=7.5625f*q*q+0.984375f; }
    }
    const float result=a.v+(b.v-a.v)*p;
    return std::isfinite(result)?result:a.v;
}
float parameterValue(const std::vector<Parameter>& parameters,int effect,const char* key,
                     float fallback,float low,float high,float time) {
    float value=fallback;
    for(const auto& parameter:parameters) {
        if(parameter.effect==effect && parameter.name==key) {
            value=evaluateKeys(parameter,time,fallback); break;
        }
    }
    if(!std::isfinite(value)) value=fallback;
    return std::clamp(value,low,high);
}
struct RuntimeEffect { const Definition* definition; float p[7]; };
struct Color { float r,g,b,a; };
float fract(float v) { return v-std::floor(v); }
float waveShape(float t,float shape) {
    const float s=std::sin(t);
    if(shape<0.5f) return s;
    if(shape<1.5f) return s>0.0f?1.0f:(s<0.0f?-1.0f:0.0f);
    const float c=fract(t/(2.0f*kPi));
    if(shape<2.5f) return 1.0f-4.0f*std::fabs(c-0.5f);
    return c*2.0f-1.0f;
}
Color sample(const std::vector<uint8_t>& pixels,int width,int height,float u,float v) {
    const float x=std::clamp(u,0.0f,1.0f)*static_cast<float>(width-1);
    const float y=std::clamp(v,0.0f,1.0f)*static_cast<float>(height-1);
    const int x0=static_cast<int>(x), y0=static_cast<int>(y);
    const int x1=std::min(x0+1,width-1), y1=std::min(y0+1,height-1);
    const float fx=x-static_cast<float>(x0), fy=y-static_cast<float>(y0);
    const auto at=[&](int px,int py,int channel) {
        return static_cast<float>(pixels[(static_cast<std::size_t>(py)*width+px)*4+channel])/255.0f;
    };
    Color c{};
    float* channels[4]={&c.r,&c.g,&c.b,&c.a};
    for(int ch=0;ch<4;++ch) {
        const float top=at(x0,y0,ch)+(at(x1,y0,ch)-at(x0,y0,ch))*fx;
        const float bottom=at(x0,y1,ch)+(at(x1,y1,ch)-at(x0,y1,ch))*fx;
        *channels[ch]=top+(bottom-top)*fy;
    }
    return c;
}
void applyWarp(float& u,float& v,const RuntimeEffect& effect,float time) {
    const auto& p=effect.p;
    switch(effect.definition->id) {
        case 1: {
            const float outW=std::max(p[2]/100.0f,1.0f);
            const float outH=std::max(p[3]/100.0f,1.0f);
            const float tileW=std::max(p[0]/100.0f,0.01f);
            const float tileH=std::max(p[1]/100.0f,0.01f);
            const float qx=std::clamp(((u-0.5f)*outW)/tileW+0.5f+p[5],-2048.0f,2048.0f);
            const float qy=std::clamp(((v-0.5f)*outH)/tileH+0.5f+p[6],-2048.0f,2048.0f);
            const float cx=std::floor(qx), cy=std::floor(qy);
            float px=fract(qx), py=fract(qy);
            if(p[4]>0.5f) {
                if(std::fmod(std::fabs(cx),2.0f)>=1.0f) px=1.0f-px;
                if(std::fmod(std::fabs(cy),2.0f)>=1.0f) py=1.0f-py;
            }
            u=px; v=py; break;
        }
        case 2: {
            const float angle=p[4]*kPi/180.0f;
            const float ax=std::cos(angle), ay=std::sin(angle);
            const float px=-ay, py=ax;
            const float t=((u-0.5f)*ax+(v-0.5f)*ay)*std::max(p[1],1.0f)*2.0f*kPi-time*p[2]*2.0f*kPi+p[5]*kPi/180.0f;
            const float wave=waveShape(t,p[3]);
            float edge=1.0f;
            if(p[6]==1.0f || p[6]==3.0f) edge*=std::clamp(v/0.08f,0.0f,1.0f)*std::clamp((1.0f-v)/0.08f,0.0f,1.0f);
            if(p[6]==2.0f || p[6]==3.0f) edge*=std::clamp(u/0.08f,0.0f,1.0f)*std::clamp((1.0f-u)/0.08f,0.0f,1.0f);
            u+=px*wave*p[0]*edge; v+=py*wave*p[0]*edge; break;
        }
        case 3: {
            const float t=time*p[2]+p[4]*17.17f;
            float sx,sy;
            if(p[3]>0.5f) {
                sx=fract(std::sin(std::floor(t)*12.9898f+p[4]*78.233f)*43758.5453f)*2.0f-1.0f;
                sy=fract(std::sin(std::floor(t+0.37f)*39.346f+p[4]*11.135f)*24634.6345f)*2.0f-1.0f;
            } else { sx=std::sin(t*2.0f*kPi); sy=std::sin(t*2.0f*kPi+2.17f); }
            const float angle=p[5]*kPi/180.0f;
            const float dx=std::cos(angle), dy=std::sin(angle);
            u+=dx*(sx*p[0])-dy*(sy*p[1]);
            v+=dy*(sx*p[0])+dx*(sy*p[1]); break;
        }
        case 5: {
            const float t=time*p[2]*2.0f*kPi+p[4]*kPi/180.0f;
            float wave=waveShape(t,p[3]);
            if(p[5]>0.5f) wave=(wave+1.0f)*0.5f;
            u+=wave*p[0]; v+=std::sin(t+1.5708f)*p[1]; break;
        }
        default: break;
    }
}
float clamp01(float value) { return std::clamp(value,0.0f,1.0f); }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_kinetic_app_engine_NativeEffectsEngine_nativeCreateEffect(JNIEnv* env,jobject,jstring name) {
    if(!name) return nullptr;
    const char* chars=env->GetStringUTFChars(name,nullptr);
    if(!chars) return nullptr;
    const Definition* definition=findDefinition(chars);
    env->ReleaseStringUTFChars(name,chars);
    jclass stringClass=env->FindClass("java/lang/String");
    if(!stringClass) return nullptr;
    if(!definition) return env->NewObjectArray(0,stringClass,nullptr);
    jobjectArray result=env->NewObjectArray(definition->count*2,stringClass,nullptr);
    if(!result) return nullptr;
    for(int i=0;i<definition->count;++i) {
        jstring key=env->NewStringUTF(definition->keys[i]);
        char number[32];
        std::snprintf(number,sizeof(number),"%.9g",static_cast<double>(definition->defaults[i]));
        jstring value=env->NewStringUTF(number);
        if(!key||!value) return nullptr;
        env->SetObjectArrayElement(result,i*2,key);
        env->SetObjectArrayElement(result,i*2+1,value);
        env->DeleteLocalRef(key); env->DeleteLocalRef(value);
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kinetic_app_engine_NativeEffectsEngine_nativeProcessBitmap(
    JNIEnv* env,jobject,jobject bitmap,jobjectArray effectNames,jbooleanArray enabled,
    jobjectArray parameterNames,jfloatArray parameterValues,jintArray parameterEffectIndices,
    jintArray keyframeParameterIndices,jfloatArray keyframeData,jfloat time) {
    if(!bitmap||!effectNames||!enabled||!parameterNames||!parameterValues||
       !parameterEffectIndices||!keyframeParameterIndices||!keyframeData) return JNI_FALSE;
    const jsize effectCount=env->GetArrayLength(effectNames);
    const jsize parameterCount=env->GetArrayLength(parameterNames);
    const jsize keyCount=env->GetArrayLength(keyframeParameterIndices);
    if(effectCount>256||parameterCount>4096||keyCount>16384||
       env->GetArrayLength(enabled)!=effectCount||
       env->GetArrayLength(parameterValues)!=parameterCount||
       env->GetArrayLength(parameterEffectIndices)!=parameterCount||
       env->GetArrayLength(keyframeData)!=keyCount*7) return JNI_FALSE;

    AndroidBitmapInfo info{};
    if(AndroidBitmap_getInfo(env,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS||
       info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888||info.width==0||info.height==0||
       info.width>2048||info.height>2048) return JNI_FALSE;
    std::vector<jboolean> active(static_cast<std::size_t>(effectCount));
    std::vector<jfloat> bases(static_cast<std::size_t>(parameterCount));
    std::vector<jint> owners(static_cast<std::size_t>(parameterCount));
    std::vector<jint> keyOwners(static_cast<std::size_t>(keyCount));
    std::vector<jfloat> keyValues(static_cast<std::size_t>(keyCount)*7);
    if(effectCount) env->GetBooleanArrayRegion(enabled,0,effectCount,active.data());
    if(parameterCount) {
        env->GetFloatArrayRegion(parameterValues,0,parameterCount,bases.data());
        env->GetIntArrayRegion(parameterEffectIndices,0,parameterCount,owners.data());
    }
    if(keyCount) {
        env->GetIntArrayRegion(keyframeParameterIndices,0,keyCount,keyOwners.data());
        env->GetFloatArrayRegion(keyframeData,0,keyCount*7,keyValues.data());
    }
    if(env->ExceptionCheck()) { env->ExceptionClear(); return JNI_FALSE; }
    std::vector<std::string> names(static_cast<std::size_t>(effectCount));
    std::vector<std::string> keys(static_cast<std::size_t>(parameterCount));
    for(jsize i=0;i<effectCount;++i) {
        jstring item=static_cast<jstring>(env->GetObjectArrayElement(effectNames,i));
        if(item) { const char* s=env->GetStringUTFChars(item,nullptr); if(s){names[i]=s;env->ReleaseStringUTFChars(item,s);} env->DeleteLocalRef(item); }
    }
    for(jsize i=0;i<parameterCount;++i) {
        jstring item=static_cast<jstring>(env->GetObjectArrayElement(parameterNames,i));
        if(item) { const char* s=env->GetStringUTFChars(item,nullptr); if(s){keys[i]=s;env->ReleaseStringUTFChars(item,s);} env->DeleteLocalRef(item); }
    }
    std::vector<Parameter> parameters;
    parameters.reserve(static_cast<std::size_t>(parameterCount));
    for(jsize i=0;i<parameterCount;++i) {
        if(owners[i]<0||owners[i]>=effectCount) continue;
        parameters.push_back({owners[i],keys[i],bases[i],{}});
    }
    for(jsize i=0;i<keyCount;++i) {
        const jint index=keyOwners[i];
        if(index<0||index>=parameterCount) continue;
        auto found=std::find_if(parameters.begin(),parameters.end(),[&](const Parameter& p) {
            return p.effect==owners[index]&&p.name==keys[index];
        });
        if(found==parameters.end()) continue;
        const std::size_t o=static_cast<std::size_t>(i)*7;
        Key key{keyValues[o],keyValues[o+1],static_cast<int>(std::lround(keyValues[o+2])),
                keyValues[o+3],keyValues[o+4],keyValues[o+5],keyValues[o+6]};
        if(std::isfinite(key.t)&&std::isfinite(key.v)) found->keys.push_back(key);
    }
    for(auto& parameter:parameters)
        std::stable_sort(parameter.keys.begin(),parameter.keys.end(),[](const Key& a,const Key& b){return a.t<b.t;});

    std::vector<RuntimeEffect> effects;
    effects.reserve(8);
    for(jsize e=0;e<effectCount&&effects.size()<8;++e) {
        if(active[e]!=JNI_TRUE) continue;
        const Definition* definition=findDefinition(names[e]);
        if(!definition) continue;
        RuntimeEffect runtime{}; runtime.definition=definition;
        for(int p=0;p<definition->count;++p)
            runtime.p[p]=parameterValue(parameters,e,definition->keys[p],definition->defaults[p],
                                        definition->minimums[p],definition->maximums[p],time);
        effects.push_back(runtime);
    }
    if(effects.empty()) return JNI_TRUE;

    void* locked=nullptr;
    if(AndroidBitmap_lockPixels(env,bitmap,&locked)!=ANDROID_BITMAP_RESULT_SUCCESS||!locked) return JNI_FALSE;
    const int width=static_cast<int>(info.width), height=static_cast<int>(info.height);
    const std::size_t pixelBytes=static_cast<std::size_t>(width)*height*4u;
    std::vector<uint8_t> source(pixelBytes), output(pixelBytes);
    const auto* input=static_cast<const uint8_t*>(locked);
    for(int y=0;y<height;++y)
        std::memcpy(source.data()+static_cast<std::size_t>(y)*width*4u,
                    input+static_cast<std::size_t>(y)*info.stride,static_cast<std::size_t>(width)*4u);

    for(int y=0;y<height;++y) {
        for(int x=0;x<width;++x) {
            float u=(static_cast<float>(x)+0.5f)/static_cast<float>(width);
            float v=(static_cast<float>(y)+0.5f)/static_cast<float>(height);
            for(const auto& effect:effects) applyWarp(u,v,effect,time);
            u=clamp01(u); v=clamp01(v);
            Color color=sample(source,width,height,u,v);
            for(const auto& effect:effects) {
                const auto& p=effect.p;
                switch(effect.definition->id) {
                    case 4: {
                        float dx=std::cos(p[3]*kPi/180.0f), dy=std::sin(p[3]*kPi/180.0f);
                        if(p[4]>0.5f) {
                            dx=u-p[5]; dy=v-p[6];
                            const float length=std::sqrt(dx*dx+dy*dy);
                            if(length>0.00001f) { dx/=length; dy/=length; } else { dx=1.0f; dy=0.0f; }
                        }
                        const Color red=sample(source,width,height,u+dx*p[0],v+dy*p[0]);
                        const Color green=sample(source,width,height,u+dx*p[1],v+dy*p[1]);
                        const Color blue=sample(source,width,height,u+dx*p[2],v+dy*p[2]);
                        color.r=red.r; color.g=green.g; color.b=blue.b; break;
                    }
                    case 6: {
                        const float radius=std::max(p[1]*p[4],0.0005f);
                        float gr=0,gg=0,gb=0;
                        const int taps=p[3]>0.5f?8:4;
                        const float offsets[8][2]={{radius,0},{-radius,0},{0,radius},{0,-radius},
                            {radius*0.7071f,radius*0.7071f},{-radius*0.7071f,radius*0.7071f},
                            {radius*0.7071f,-radius*0.7071f},{-radius*0.7071f,-radius*0.7071f}};
                        for(int tap=0;tap<taps;++tap) {
                            const Color s=sample(source,width,height,u+offsets[tap][0],v+offsets[tap][1]);
                            gr+=std::max(s.r-p[0],0.0f); gg+=std::max(s.g-p[0],0.0f); gb+=std::max(s.b-p[0],0.0f);
                        }
                        const float divisor=taps==8?8.0f:4.0f;
                        gr/=divisor; gg/=divisor; gb/=divisor;
                        const float luminance=gr*0.299f+gg*0.587f+gb*0.114f;
                        gr=luminance+(gr-luminance)*p[5];
                        gg=luminance+(gg-luminance)*p[5];
                        gb=luminance+(gb-luminance)*p[5];
                        if(p[6]>0.5f) { color.r=gr*p[2]; color.g=gg*p[2]; color.b=gb*p[2]; }
                        else { color.r+=gr*p[2]; color.g+=gg*p[2]; color.b+=gb*p[2]; }
                        break;
                    }
                    case 7: {
                        const float exposure=std::exp2(p[0]);
                        const float invGamma=1.0f/std::max(p[1],0.01f);
                        const auto adjust=[&](float channel) {
                            const float value=std::max(channel+p[2],0.0f)*exposure;
                            return std::pow(value,invGamma);
                        };
                        if(p[3]<0.5f) { color.r=adjust(color.r); color.g=adjust(color.g); color.b=adjust(color.b); }
                        else if(p[3]<1.5f) color.r=adjust(color.r);
                        else if(p[3]<2.5f) color.g=adjust(color.g);
                        else color.b=adjust(color.b);
                        break;
                    }
                    default: break;
                }
            }
            const std::size_t offset=(static_cast<std::size_t>(y)*width+x)*4u;
            output[offset]=static_cast<uint8_t>(std::lround(clamp01(color.r)*255.0f));
            output[offset+1]=static_cast<uint8_t>(std::lround(clamp01(color.g)*255.0f));
            output[offset+2]=static_cast<uint8_t>(std::lround(clamp01(color.b)*255.0f));
            output[offset+3]=static_cast<uint8_t>(std::lround(clamp01(color.a)*255.0f));
        }
    }
    auto* destination=static_cast<uint8_t*>(locked);
    for(int y=0;y<height;++y)
        std::memcpy(destination+static_cast<std::size_t>(y)*info.stride,
                    output.data()+static_cast<std::size_t>(y)*width*4u,static_cast<std::size_t>(width)*4u);
    AndroidBitmap_unlockPixels(env,bitmap);
    return JNI_TRUE;
}
