param([switch]$EmulatorTests, [switch]$FullWeChatCycle)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$previousOutput = $env:FOCUS_BUILD_ROOT
try {
    if (-not $env:JAVA_HOME) {
        $javaExe = (Get-Command java -ErrorAction Stop).Source
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaExe)
    }
    if (-not $env:FOCUS_BUILD_ROOT) {
        $env:FOCUS_BUILD_ROOT = Join-Path $env:LOCALAPPDATA 'Temp/scheduled-focus-build'
    }
    Push-Location -LiteralPath $projectRoot
    try {
        $buildTasks = @(':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleRelease')
        if ($EmulatorTests -or $FullWeChatCycle) { $buildTasks += ':app:connectedDebugAndroidTest' }
        if ($FullWeChatCycle) { $buildTasks += '-Pandroid.testInstrumentationRunnerArguments.fullWeChatCycle=true' }
        & (Join-Path $projectRoot 'gradlew.bat') @buildTasks --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Gradle checks or build failed.' }
        $deliveryDir = Join-Path $projectRoot 'dist'
        New-Item -ItemType Directory -Path $deliveryDir -Force | Out-Null
        $versionText = Get-Content -LiteralPath (Join-Path $projectRoot 'app/build.gradle.kts') -Raw
        $releaseVersion = [regex]::Match($versionText, 'versionName = .*?: "([^"]+)"').Groups[1].Value
        if (-not $releaseVersion) { throw 'Unable to read versionName.' }
        $apkName = "scheduled-focus-v$releaseVersion.apk"
        $apkPath = Join-Path $deliveryDir $apkName
        Copy-Item -LiteralPath (Join-Path $env:FOCUS_BUILD_ROOT 'app/outputs/apk/release/app-release.apk') -Destination $apkPath
        $digest = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        Set-Content -LiteralPath (Join-Path $deliveryDir 'SHA256SUMS.txt') -Value "$digest  $apkName" -Encoding ascii
        Write-Output "APK: $apkPath"
        Write-Output "SHA256: $digest"
    } finally { Pop-Location }
} finally { $env:FOCUS_BUILD_ROOT = $previousOutput }
