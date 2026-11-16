$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$testJava = 'C:/Program Files/Java/jdk-21/bin'
New-Item -ItemType Directory -Force -Path '.buildlog/test-classes' | Out-Null
& "$testJava/javac.exe" -encoding UTF-8 -d .buildlog/test-classes app/src/main/java/com/kokkorylien/quickmove/FilenamePolicy.java app/src/main/java/com/kokkorylien/quickmove/TransferEngine.java tests/TransferEngineTest.java tests/FilenamePolicyTest.java
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$testJava/java.exe" -XX:ActiveProcessorCount=2 -cp .buildlog/test-classes com.kokkorylien.quickmove.TransferEngineTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$testJava/java.exe" -XX:ActiveProcessorCount=2 -cp .buildlog/test-classes com.kokkorylien.quickmove.FilenamePolicyTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$jsonTestJar = '.buildlog/test-libs/json.jar'
if (-not (Test-Path -LiteralPath $jsonTestJar)) {
    New-Item -ItemType Directory -Force -Path '.buildlog/test-libs' | Out-Null
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/json/json/20250107/json-20250107.jar' -OutFile $jsonTestJar
}
$androidTestJar = 'C:/Users/PC/.cache/codex-runtimes/android-sdk/platforms/android-36/android.jar'
$testClasspath = ".buildlog/test-classes;$jsonTestJar;$androidTestJar"
& "$testJava/javac.exe" -encoding UTF-8 -cp $testClasspath -d .buildlog/test-classes app/src/main/java/com/kokkorylien/quickmove/Store.java tests/PersistenceTest.java
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$testJava/java.exe" -XX:ActiveProcessorCount=2 -cp $testClasspath com.kokkorylien.quickmove.PersistenceTest
exit $LASTEXITCODE
