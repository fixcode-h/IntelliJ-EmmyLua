#!/usr/bin/env bash
# 等同一个提交上的测试工作流跑完：全部成功（或因路径过滤没有触发）返回 0，有一个失败 / 取消就返回 1。
# 用法：wait-for-checks.sh <sha> <workflow 文件名>...   需要 GH_TOKEN、GH_REPO。
#
# 提速的两点（出包等待曾经要 14 分钟）：
# - 同一个提交在别的分支（通常是 dev，main 快进合并时提交号相同）已经跑通过，直接算通过，不再等 main 上那次。
# - 「没触发」的判定用一个共同的起点：以前每个工作流各等 3 分钟，没触发的两个工作流白白多等 6 分钟。
set -euo pipefail

sha="$1"
shift

# 工作流和出包同时被 push 触发，可能要几十秒才登记出来；从开始等起超过这个时间还没有就当作没触发
appear_timeout=180
# 最慢的 Verify Plugin 大约 10 分钟，留足余量
finish_timeout=3600
poll=15

start=$SECONDS
declare -A done_wf=()
failed=0

while :; do
  remaining=0
  for wf in "$@"; do
    [ -n "${done_wf[$wf]:-}" ] && continue
    runs=$(gh run list --workflow "$wf" --commit "$sha" --limit 50 --json databaseId,status,conclusion,event,headBranch \
      -q '[.[] | select(.event == "push")]')
    # 任何分支上这个提交已经通过：直接算通过
    ok=$(jq -r '[.[] | select(.status == "completed" and .conclusion == "success")] | sort_by(.databaseId) | last | "\(.databaseId) \(.headBranch)" // empty' <<<"$runs")
    if [ -n "$ok" ] && [ "$ok" != "null null" ]; then
      echo "$wf：通过（run ${ok% *}，分支 ${ok#* }）"
      done_wf[$wf]=1
      continue
    fi
    count=$(jq 'length' <<<"$runs")
    if [ "$count" -eq 0 ]; then
      if [ $((SECONDS - start)) -ge $appear_timeout ]; then
        echo "$wf：这个提交没有触发（路径过滤），跳过"
        done_wf[$wf]=1
      else
        remaining=1
      fi
      continue
    fi
    pending=$(jq '[.[] | select(.status != "completed")] | length' <<<"$runs")
    if [ "$pending" -gt 0 ]; then
      if [ $((SECONDS - start)) -ge $finish_timeout ]; then
        echo "::error::$wf 超过 $((finish_timeout / 60)) 分钟还没跑完"
        failed=1
        done_wf[$wf]=1
      else
        remaining=1
      fi
      continue
    fi
    # 全部跑完且没有一次成功：以最新一次为准报失败
    latest=$(jq -r 'sort_by(.databaseId) | last | .conclusion' <<<"$runs")
    id=$(jq -r 'sort_by(.databaseId) | last | .databaseId' <<<"$runs")
    echo "::error::$wf 没有通过（$latest），不发版：https://github.com/$GH_REPO/actions/runs/$id"
    failed=1
    done_wf[$wf]=1
  done
  [ "$remaining" -eq 0 ] && break
  sleep "$poll"
done
exit $failed
