[CmdletBinding(DefaultParameterSetName = "Layout")]
param(
    [Parameter(Mandatory = $true, ParameterSetName = "Layout")]
    [string]$BuildRoot,
    [Parameter(Mandatory = $true, ParameterSetName = "CMake")]
    [string]$X86BuildDir,
    [Parameter(Mandatory = $true, ParameterSetName = "CMake")]
    [string]$X64BuildDir,
    [ValidateSet("Debug", "Release", "RelWithDebInfo")]
    [string]$Configuration = "Release",
    [Parameter(Mandatory = $true)]
    [string]$OutputRoot
)

$ErrorActionPreference = "Stop"
$files = @("EasyHook.dll", "emmy_core.dll", "emmy_hook.dll", "emmy_tool.exe")

$artifacts = @()
foreach ($architecture in @("x86", "x64")) {
    $source = if ($PSCmdlet.ParameterSetName -eq "CMake") {
        if ($architecture -eq "x86") { $X86BuildDir } else { $X64BuildDir }
    } else { Join-Path $BuildRoot $architecture }
    $destination = Join-Path $OutputRoot $architecture
    foreach ($name in $files) {
        $targetDirectory = if ($name -eq "EasyHook.dll") { "third-party/EasyHook" } else {
            [System.IO.Path]::GetFileNameWithoutExtension($name)
        }
        $candidates = @((Join-Path $source $name))
        if ($PSCmdlet.ParameterSetName -eq "CMake") {
            $candidates += Join-Path $source "$targetDirectory/$name"
            $candidates += Join-Path $source "$targetDirectory/$Configuration/$name"
        }
        $artifactSource = $candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
        if (-not $artifactSource -or (Get-Item -LiteralPath $artifactSource).Length -eq 0) {
            throw "缺少或为空的 Native 产物: $architecture/$name ($source)"
        }
        $artifacts += [pscustomobject]@{ Source = $artifactSource; Directory = $destination; Name = $name }
    }
}

# 先校验完整输入，再复制到独立打包目录。
foreach ($artifact in $artifacts) {
    New-Item -ItemType Directory -Force -Path $artifact.Directory | Out-Null
    Copy-Item -LiteralPath $artifact.Source -Destination (Join-Path $artifact.Directory $artifact.Name) -Force
}
Get-FileHash -LiteralPath ($artifacts | ForEach-Object { Join-Path $_.Directory $_.Name }) -Algorithm SHA256 |
    Select-Object Algorithm, Hash, Path
