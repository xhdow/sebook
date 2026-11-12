# Builds sebook (the encrypted notebook) without Maven or Gradle.
#
#   .\build.ps1            compile everything into out\ and package sebook.jar
#   .\build.ps1 -Test      also compile and run the self-check suite
#   .\build.ps1 -Clean     remove build output first
#
# Requires JDK 17+ on PATH. Third-party jars are expected in lib\.

param(
    [switch]$Test,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location $root

if ($Clean) {
    Remove-Item -Recurse -Force out, dist -ErrorAction SilentlyContinue
}

New-Item -ItemType Directory -Force -Path out, dist | Out-Null

$libJars = @(Get-ChildItem lib\*.jar -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
if ($libJars.Count -eq 0) {
    throw "no jars found in lib\; see README for the required dependencies"
}
$libCp = $libJars -join ';'

Write-Host "compiling main sources..."
$mainSources = @(Get-ChildItem -Recurse src\main\java -Filter *.java | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 -d out -cp $libCp $mainSources
if ($LASTEXITCODE -ne 0) { throw "main compilation failed" }

Write-Host "packaging dist\sebook.jar..."
$manifest = @"
Manifest-Version: 1.0
Main-Class: sebook.cli.SebookCli
Class-Path: $((Get-ChildItem lib\*.jar | ForEach-Object { "lib/" + $_.Name }) -join ' ')
"@
$manifestPath = Join-Path $root 'out\MANIFEST.MF'
[System.IO.File]::WriteAllText($manifestPath, ($manifest -replace "`r?`n", "`r`n") + "`r`n")
& jar --create --file dist\sebook.jar --manifest $manifestPath -C out sebook
if ($LASTEXITCODE -ne 0) { throw "jar packaging failed" }
Copy-Item -Recurse -Force lib dist\lib

Write-Host "writing dist\filetool.cmd..."
$filetool = @"
@echo off
java -Dfile.encoding=UTF-8 -cp "%~dp0sebook.jar;%~dp0lib\*" sebook.cli.FileToolCli %*
"@
[System.IO.File]::WriteAllText((Join-Path $root 'dist\filetool.cmd'), ($filetool -replace "`r?`n", "`r`n"))

if ($Test) {
    Write-Host "compiling and running self-check..."
    $testCp = ($libCp + ';' + (Resolve-Path out).Path)
    $testSources = @(Get-ChildItem -Recurse src\test\java -Filter *.java | ForEach-Object { $_.FullName })
    & javac -encoding UTF-8 -d out -cp $testCp $testSources
    if ($LASTEXITCODE -ne 0) { throw "test compilation failed" }
    & java "-Dfile.encoding=UTF-8" -cp $testCp sebook.SelfCheck
    if ($LASTEXITCODE -ne 0) { throw "self-check failed" }
}

Write-Host ""
Write-Host "done. run with:"
Write-Host "  java -jar dist\sebook.jar [vault-file]"
Write-Host "  dist\filetool.cmd encrypt|decrypt|info ..."
