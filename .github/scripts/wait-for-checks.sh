#!/usr/bin/env bash
# 等同一个提交上的测试工作流跑完：全部成功（或因路径过滤没有触发）返回 0，有一个失败 / 取消就返回 1。
# 用法：wait-for-checks.sh <sha> <workflow 文件名>...   需要 GH_TOKEN、GH_REPO。
set -euo pipefail

sha="$1"
shift

# 工作流和出包同时被 push 触发，可能要几十秒才登记出来；超过这个时间还没有就当作没触发
appear_timeout=180
# 最慢的 Verify Plugin 大约 10 分钟，留足余量
finish_timeout=3600
poll=30

failed=0
for wf in "$@"; do
  start=$SECONDS
  while :; do
    runs=$(gh run list --workflow "$wf" --commit "$sha" --limit 20 --json databaseId,status,conclusion,event \
      -q '[.[] | select(.event == "push")]')
    count=$(jq 'length' <<<"$runs")
    if [ "$count" -eq 0 ]; then
      if [ $((SECONDS - start)) -ge $appear_timeout ]; then
        echo "$wf：这个提交没有触发（路径过滤），跳过"
        break
      fi
      sleep "$poll"
      continue
    fi
    pending=$(jq '[.[] | select(.status != "completed")] | length' <<<"$runs")
    if [ "$pending" -gt 0 ]; then
      if [ $((SECONDS - start)) -ge $finish_timeout ]; then
        echo "::error::$wf 超过 $((finish_timeout / 60)) 分钟还没跑完"
        failed=1
        break
      fi
      sleep "$poll"
      continue
    fi
    # 同一个提交可能重跑过：以最新一次为准
    latest=$(jq -r 'sort_by(.databaseId) | last | .conclusion' <<<"$runs")
    id=$(jq -r 'sort_by(.databaseId) | last | .databaseId' <<<"$runs")
    if [ "$latest" = "success" ]; then
      echo "$wf：通过（run $id）"
    else
      echo "::error::$wf 没有通过（$latest），不发版：https://github.com/$GH_REPO/actions/runs/$id"
      failed=1
    fi
    break
  done
done
exit $failed
