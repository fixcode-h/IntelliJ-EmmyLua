#requires -RunAsAdministrator
<#
仅在操作者明确同意临时防火墙放行后运行。
针对两个 Native 测试程序添加 127.0.0.1 入站规则，执行 CTest 后移除本次创建的规则。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BuildDir,
    [Parameter(Mandatory = $true)]
    [switch]$AllowTemporaryFirewallRules
)

$ErrorActionPreference = 'Stop'
if (-not $AllowTemporaryFirewallRules) {
    throw '需要显式传入 -AllowTemporaryFirewallRules，表示已同意本次临时放行。'
}
$nativeBuild = (Resolve-Path -LiteralPath $BuildDir).Path
$nativePrograms = @(
    (Join-Path $nativeBuild 'emmy_debugger/emmy_transporter_concurrency_test.exe'),
    (Join-Path $nativeBuild 'emmy_debugger/emmy_native_socket_harness.exe')
)
foreach ($nativeProgram in $nativePrograms) {
    if (-not (Test-Path -LiteralPath $nativeProgram -PathType Leaf)) {
        throw "测试程序不存在：$nativeProgram"
    }
}
if (-not (Get-Command ctest -ErrorAction SilentlyContinue)) { throw '找不到 ctest。' }

$createdRules = [System.Collections.Generic.List[string]]::new()
$cleanupErrors = [System.Collections.Generic.List[string]]::new()
$rulePrefix = 'EmmyLua-Native-TCP-Test-' + [guid]::NewGuid().ToString('N')
try {
    for ($index = 0; $index -lt $nativePrograms.Count; $index++) {
        $ruleName = "$rulePrefix-$index"
        New-NetFirewallRule -Name $ruleName -DisplayName $ruleName `
            -Direction Inbound -Action Allow -Protocol TCP -Profile Any `
            -Program $nativePrograms[$index] -LocalAddress '127.0.0.1' `
            -RemoteAddress '127.0.0.1' -EdgeTraversalPolicy Block | Out-Null
        $createdRules.Add($ruleName)
    }
    & ctest --test-dir $nativeBuild `
        -R '^(emmy_transporter_concurrency_test|emmy_native_socket_harness)$' `
        --output-on-failure --no-tests=error --timeout 30 --output-junit tcp-approved-results.xml
    $tcpTestExitCode = $LASTEXITCODE
} finally {
    foreach ($createdRule in $createdRules) {
        try { Remove-NetFirewallRule -Name $createdRule -ErrorAction Stop }
        catch { $cleanupErrors.Add("$createdRule : $($_.Exception.Message)") }
    }
    if ($cleanupErrors.Count -gt 0) { throw "临时规则清理失败：$($cleanupErrors -join '; ')" }
}
exit $tcpTestExitCode
