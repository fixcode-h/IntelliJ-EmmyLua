/*
 * 宿主值提供者 C ABI v2（docs/design/variable-view.md 13.6）。
 * 宿主（例如 UEKit 的 UELink）按导出名 Emmy_RegisterHostValueProviderV2 找到注册函数，把引擎对象的摘要和子项
 * 交给调试器显示。UELink 侧有一份副本（UELinkEmmyHostValueAbi.h），两边改动要同步。
 */
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "emmy/emmy_core.h"

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#  define EMMY_HOST_API_CALL __cdecl
#else
#  define EMMY_HOST_API_CALL
#endif

#define EMMY_HOST_VALUE_ABI_VERSION 2u

/* 回调返回值（和 v1 数值一致） */
typedef enum EmmyHostValueStatus {
	EMMY_HOST_VALUE_OK = 0,                /* 认领并写了结果 */
	EMMY_HOST_VALUE_UNAVAILABLE = 1,       /* 不认领：交给 helper / 默认显示 */
	EMMY_HOST_VALUE_NEEDS_GAME_THREAD = 2, /* 不在能读引擎对象的线程上 */
	EMMY_HOST_VALUE_OBJECT_INVALID = 3,    /* 认领了，但对象已失效（结果 valid=false） */
	EMMY_HOST_VALUE_FIELD_DENIED = 4,
	EMMY_HOST_VALUE_TIMEOUT = 5
} EmmyHostValueStatus;

typedef void (EMMY_HOST_API_CALL *EmmyHostWriteFn)(void* sink, const char* utf8, size_t length);

typedef struct EmmyHostValueQuery {
	uint32_t size, version;
	uint64_t vmId;
	uint64_t pauseId;          /* 同一暂停内宿主发出的 childRef 有效 */
	struct lua_State* L;       /* 暂停所在的 lua_State，只在本次调用内有效；hostRef 非空时可为 NULL */
	int32_t index;             /* 被查询的 userdata / table 在 L 栈上的绝对索引 */
	const char* hostRef;       /* 宿主之前给出的 childRef；NULL 表示查询栈上的值 */
	uint32_t start, count;     /* listChildren 分页 */
	uint32_t maxValueLength;
	uint64_t deadlineUnixMillis;
	void* sink;
	EmmyHostWriteFn write;     /* 宿主把 UTF-8 JSON 结果写进来，可分多次 */
} EmmyHostValueQuery;

typedef int  (EMMY_HOST_API_CALL *EmmyHostQueryFn)(void* userData, const EmmyHostValueQuery* query);
typedef void (EMMY_HOST_API_CALL *EmmyHostReleaseFn)(void* userData, uint64_t vmId, uint64_t pauseId);

typedef struct EmmyHostValueProviderV2 {
	uint32_t size, version;
	void* userData;
	const char* name;
	EmmyHostQueryFn describe;       /* 写 {valid, typeName, summary, hint?, kind, expandable} */
	EmmyHostQueryFn listChildren;   /* 写 {valid, total, children:[{name, type, value, typeName?, kind?, attrs?, hint?, length?, expandable?, childRef?}]} */
	EmmyHostReleaseFn releasePause; /* 暂停结束时调用，宿主释放这次暂停发出的句柄 */
} EmmyHostValueProviderV2;

/* 返回提供者 id（0 表示参数不对）。provider 内容会被复制，name 也会复制。 */
EMMY_API uint64_t EMMY_HOST_API_CALL Emmy_RegisterHostValueProviderV2(const EmmyHostValueProviderV2* provider);
/* 成功返回 0。注销后不再发起新的回调；回调都在暂停循环里、VM 线程上发出。 */
EMMY_API int EMMY_HOST_API_CALL Emmy_UnregisterHostValueProviderV2(uint64_t providerId);

#ifdef __cplusplus
}
#endif
