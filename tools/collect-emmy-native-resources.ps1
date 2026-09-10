param(
    [Parameter(Mandatory = $true)]
    [string]$BuildRoot,
    [Parameter(Mandatory = $true)]
    [string]$OutputRoot
)

$ErrorActionPreference = "Stop"
$files = @("EasyHook.dll", "emmy_core.dll", "emmy_hook.dll", "emmy_tool.exe")

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
foreach ($architecture in @("x86", "x64")) {
    $source = Join-Path $BuildRoot $architecture
    $destination = Join-Path $OutputRoot $architecture
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    foreach ($name in $files) {
        $input = Join-Path $source $name
        if (-not (Test-Path -LiteralPath $input -PathType Leaf)) {
            throw "缺少 Native 产物: $input"
        }
        Copy-Item -LiteralPath $input -Destination (Join-Path $destination $name) -Force
    }
}

Get-FileHash -LiteralPath (Get-ChildItem -LiteralPath $OutputRoot -Recurse -File | Select-Object -ExpandProperty FullName) -Algorithm SHA256 |
    Select-Object Algorithm, Hash, Path
