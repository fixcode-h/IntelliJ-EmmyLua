// 日志：Android 上写 logcat（tag EmmyLuaAgent，IDE 报错时提示用户看这个 tag），主机测试写 stderr。
#pragma once

#if defined(__ANDROID__)
#include <android/log.h>
#define EMMY_AGENT_TAG "EmmyLuaAgent"
#define AGENT_LOGI(...) __android_log_print(ANDROID_LOG_INFO, EMMY_AGENT_TAG, __VA_ARGS__)
#define AGENT_LOGW(...) __android_log_print(ANDROID_LOG_WARN, EMMY_AGENT_TAG, __VA_ARGS__)
#define AGENT_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, EMMY_AGENT_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define AGENT_LOGI(...) (std::fprintf(stderr, "[EmmyLuaAgent] " __VA_ARGS__), std::fputc('\n', stderr))
#define AGENT_LOGW(...) (std::fprintf(stderr, "[EmmyLuaAgent][W] " __VA_ARGS__), std::fputc('\n', stderr))
#define AGENT_LOGE(...) (std::fprintf(stderr, "[EmmyLuaAgent][E] " __VA_ARGS__), std::fputc('\n', stderr))
#endif
