# 荣耀卡面工坊 — Windows 构建脚本（PowerShell 7 / Windows PowerShell 5.1）
#
# 用法：
#   $env:JAVA_HOME    = "C:\path\to\jdk-17"
#   $env:ANDROID_HOME = "C:\path\to\android-sdk"
#   pwsh -File scripts/build.ps1                          # 默认 :app:assembleDebug
#   pwsh -File scripts/build.ps1 -Tasks "clean :app:assembleDebug"
#
# 也可用 -GradleCmd 指定 gradle 可执行文件；未指定时依次尝试
#   .\gradlew.bat -> %GRADLE_HOME%\bin\gradle.bat -> PATH 中的 gradle。
#
# 说明：本脚本使用一个构建锁文件（.build.lock）串行化 Gradle 调用，
#       避免多个构建同时写 build/ 目录。

param(
    [string]$Tasks = ":app:assembleDebug",
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$GradleCmd = "",
    [switch]$NoDaemon,
    [int]$LockTimeoutSec = 1800
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

if (-not $JavaHome -or -not (Test-Path $JavaHome)) {
    throw "JAVA_HOME 未设置或不存在。请设置 env:JAVA_HOME 为 JDK 17 目录（或用 -JavaHome 指定）。"
}
if (-not $AndroidHome -or -not (Test-Path $AndroidHome)) {
    throw "ANDROID_HOME 未设置或不存在。请设置 env:ANDROID_HOME 为 Android SDK 目录（或用 -AndroidHome 指定）。"
}

$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidHome
$env:ANDROID_SDK_ROOT = $AndroidHome
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle' }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

$gradle = $GradleCmd
if (-not $gradle) {
    $wrapper = Join-Path $projectRoot 'gradlew.bat'
    if (Test-Path $wrapper) {
        $gradle = $wrapper
    } elseif ($env:GRADLE_HOME -and (Test-Path (Join-Path $env:GRADLE_HOME 'bin\gradle.bat'))) {
        $gradle = Join-Path $env:GRADLE_HOME 'bin\gradle.bat'
    } else {
        $cmd = Get-Command gradle.bat -ErrorAction SilentlyContinue
        if (-not $cmd) { $cmd = Get-Command gradle -ErrorAction SilentlyContinue }
        if (-not $cmd) { throw "未找到 gradle。请安装 Gradle 8.13、生成 wrapper 或用 -GradleCmd 指定。" }
        $gradle = $cmd.Source
    }
}

# ---------------------------------------------------------------- 构建锁
$lockPath = Join-Path $projectRoot '.build.lock'
$deadline = (Get-Date).AddSeconds($LockTimeoutSec)
$lockStream = $null
while ($true) {
    try {
        $lockStream = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
        break
    } catch {
        if ((Get-Date) -ge $deadline) { throw "等待构建锁超时：$lockPath" }
        Start-Sleep -Milliseconds 500
    }
}

try {
    $gradleArgs = @($Tasks -split ' ')
    if ($NoDaemon) { $gradleArgs += '--no-daemon' }
    Push-Location $projectRoot
    try {
        & $gradle @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败（exit $LASTEXITCODE）" }
    } finally {
        Pop-Location
    }
    $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (Test-Path $apk) {
        $hash = (Get-FileHash $apk -Algorithm SHA256).Hash
        Write-Host "APK: $apk"
        Write-Host "SHA256: $hash"
    }
} finally {
    if ($lockStream) { $lockStream.Dispose() }
    Remove-Item $lockPath -ErrorAction SilentlyContinue
}
