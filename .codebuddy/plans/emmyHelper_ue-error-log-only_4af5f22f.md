---
name: emmyHelper_ue-error-log-only
overview: 简化 emmyHelper_ue.lua 的日志输出，只保留 error 级别的日志，移除 info、debug、warn 级别的日志调用。
todos:
  - id: search-log-calls
    content: 使用 [subagent:code-explorer] 搜索 emmyHelper_ue.lua 中所有 EmmyLog 日志调用
    status: completed
  - id: remove-info-logs
    content: 移除所有 EmmyLog.info 日志调用
    status: completed
    dependencies:
      - search-log-calls
  - id: remove-debug-logs
    content: 移除所有 EmmyLog.debug 日志调用
    status: completed
    dependencies:
      - search-log-calls
  - id: remove-warn-logs
    content: 移除所有 EmmyLog.warn 日志调用
    status: completed
    dependencies:
      - search-log-calls
  - id: verify-error-logs
    content: 验证 EmmyLog.error 日志调用保持完整
    status: completed
    dependencies:
      - remove-info-logs
      - remove-debug-logs
      - remove-warn-logs
---

## Product Overview

简化 emmyHelper_ue.lua 文件的日志输出功能，仅保留错误级别的日志记录，移除其他级别的日志调用。

## Core Features

- 保留 EmmyLog.error 级别的日志调用
- 移除 EmmyLog.info 级别的日志调用
- 移除 EmmyLog.debug 级别的日志调用
- 移除 EmmyLog.warn 级别的日志调用

## Agent Extensions

### SubAgent

- **code-explorer**
- Purpose: 搜索 emmyHelper_ue.lua 文件中所有的日志调用，识别 info、debug、warn、error 各级别日志的具体位置
- Expected outcome: 获取所有需要移除的日志调用位置列表，以及需要保留的 error 日志位置