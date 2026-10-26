param([string[]]$Tasks = @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
$previousJavaHome = $env:JAVA_HOME
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
$runtimePath = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
$socketPath = Join-Path (Split-Path $PSScriptRoot -Parent) '.tmp'
New-Item -ItemType Directory -Force -Path $socketPath | Out-Null
try {
    if (Test-Path -LiteralPath $runtimePath) { $env:JAVA_HOME = $runtimePath }
    # Short path avoids JDK 25 Unix-domain socket path limits in the Codex Windows sandbox.
    $env:JAVA_TOOL_OPTIONS = ($previousJavaOptions + ' -Djava.net.preferIPv4Stack=true "-Djdk.net.unixdomain.tmpdir=' + $socketPath + '"').Trim()
    Push-Location $PSScriptRoot
    try { & .\gradlew.bat @Tasks --no-daemon --console=plain; $buildExit = $LASTEXITCODE }
    finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
}
exit $buildExit
