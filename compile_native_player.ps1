$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectFile = Join-Path $projectRoot "native-player\RecordLabs.NativePlayer.csproj"
$publishDir = Join-Path $projectRoot "native-player\bin\Release\net8.0-windows10.0.22000.0\win-x64\publish"
$resourceDir = Join-Path $projectRoot "src\appResources\windows-x64"

$installedDotnet = Get-Command dotnet -ErrorAction SilentlyContinue
$dotnetCandidates = @(
    if ($installedDotnet) { $installedDotnet.Source }
    Join-Path $env:LOCALAPPDATA "RecordLabsBuildTools\dotnet\dotnet.exe"
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

$dotnetExe = $dotnetCandidates | Where-Object {
    (& $_ --list-sdks 2>$null | Select-Object -First 1) -ne $null
} | Select-Object -First 1

if (-not $dotnetExe) {
    throw "A .NET 8 SDK is required to compile the native media-key helper."
}

& $dotnetExe publish $projectFile -c Release -r win-x64 --self-contained true -p:PublishSingleFile=false
if ($LASTEXITCODE -ne 0) {
    throw "Native media-key helper compilation failed with exit code $LASTEXITCODE."
}

New-Item -ItemType Directory -Path $resourceDir -Force | Out-Null
Get-ChildItem -LiteralPath $publishDir -Force | Copy-Item -Destination $resourceDir -Recurse -Force

Write-Host "Native media-key helper compiled and copied to app resources."
