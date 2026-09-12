[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string]$PluginZip,
    [Parameter(Mandatory = $true)] [string]$NativeRoot
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$expected = @{}
foreach ($architecture in @('x86', 'x64')) {
    foreach ($name in @('EasyHook.dll', 'emmy_core.dll', 'emmy_hook.dll', 'emmy_tool.exe')) {
        $resource = "debugger/emmy/windows/$architecture/$name"
        $source = Join-Path $NativeRoot "$architecture/$name"
        $expected[$resource] = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    }
}

# 校验最终 ZIP 内的 JAR，避免只检查 processResources 中间目录。
$found = @{}
$package = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $PluginZip))
try {
    foreach ($entry in $package.Entries) {
        if (-not $entry.FullName.EndsWith('.jar')) { continue }
        $buffer = New-Object System.IO.MemoryStream
        $entryStream = $entry.Open()
        try { $entryStream.CopyTo($buffer) } finally { $entryStream.Dispose() }
        $buffer.Position = 0
        $jar = New-Object System.IO.Compression.ZipArchive($buffer, [System.IO.Compression.ZipArchiveMode]::Read)
        try {
            foreach ($resource in $jar.Entries) {
                if (-not $expected.ContainsKey($resource.FullName)) { continue }
                if ($found.ContainsKey($resource.FullName)) { throw "重复的 Native 资源: $($resource.FullName)" }
                $stream = $resource.Open()
                $sha = [System.Security.Cryptography.SHA256]::Create()
                try { $hash = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '') }
                finally { $sha.Dispose(); $stream.Dispose() }
                if ($hash -ne $expected[$resource.FullName]) { throw "Native SHA256 不匹配: $($resource.FullName)" }
                $found[$resource.FullName] = $hash
            }
        } finally { $jar.Dispose(); $buffer.Dispose() }
    }
} finally { $package.Dispose() }
foreach ($resource in $expected.Keys) {
    if (-not $found.ContainsKey($resource)) { throw "插件包缺少 Native 资源: $resource" }
}
$found.GetEnumerator() | Sort-Object Name | ForEach-Object {
    [pscustomobject]@{ Resource = $_.Key; SHA256 = $_.Value }
}
Write-Output "已验证 $($found.Count)/$($expected.Count) 个 Native 资源，均与构建输入一致。"
