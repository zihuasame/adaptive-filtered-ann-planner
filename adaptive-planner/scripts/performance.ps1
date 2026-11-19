param([switch]$Build, [switch]$ReviewBeforeOptimization)
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Push-Location $root
try {
    if ($Build) {
        & .\mvnw.cmd -B -f adaptive-planner/pom.xml '-Dtest=Planner*Test' '-Dsurefire.failIfNoSpecifiedTests=false' package 'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath' '-Dmdep.outputFile=target/classpath.txt'
        if ($LASTEXITCODE -ne 0) { throw 'Product performance build failed' }
    }
    $python = if (Test-Path .tools/python312/python.exe) { '.tools/python312/python.exe' } else { 'python' }
    $arguments = @('-B','adaptive-planner/scripts/performance-run.py')
    if (-not $ReviewBeforeOptimization) { $arguments += '--auto-after' }
    & $python @arguments
    if ($LASTEXITCODE -ne 0) { throw "Performance run failed ($LASTEXITCODE); retained all output" }
} finally { Pop-Location }
