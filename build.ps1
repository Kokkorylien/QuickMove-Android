$ErrorActionPreference = 'Stop'
$buildProcess = Get-Process -Id $PID
$buildProcess.ProcessorAffinity = [IntPtr]15
$buildProcess.PriorityClass = [System.Diagnostics.ProcessPriorityClass]::BelowNormal
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:JAVA_TOOL_OPTIONS = '-XX:ActiveProcessorCount=4'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
Set-Location -LiteralPath $PSScriptRoot
& "$PSScriptRoot/gradlew.bat" --no-daemon --max-workers=2 :app:assembleRelease :app:lintRelease
exit $LASTEXITCODE
