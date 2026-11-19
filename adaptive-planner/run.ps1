param(
    [switch]$Build,
    [switch]$Synthetic,
    [switch]$Interactive,
    [string]$Filter,
    [int]$QueryId = 0,
    [double]$Recall = 0.95,
    [int]$MaxBudget = 0,
    [switch]$Cold,
    [switch]$NoGT,
    [string]$Request
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
# Resolve the caller's request before changing directory (also works from outside the repo).
if ($Request) { $Request = (Resolve-Path -LiteralPath $Request).Path }
Push-Location $repository
try {
    $app = Join-Path $PSScriptRoot 'planner-app'
    $classpathFile = Join-Path $app 'target/classpath.txt'
    if ($Build -or -not (Test-Path -LiteralPath $classpathFile)) {
        & .\mvnw.cmd -B -f adaptive-planner/pom.xml '-Dtest=Planner*Test' '-Dsurefire.failIfNoSpecifiedTests=false' package 'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath' '-Dmdep.outputFile=target/classpath.txt'
        if ($LASTEXITCODE -ne 0) { throw "Product build failed ($LASTEXITCODE)" }
    }
    $runtimeClasspath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    # dependency:build-classpath resolves the freshly packaged reactor jars.
    $runtimeClasspath = @((Join-Path $app 'target/classes'),$runtimeClasspath) -join ';'
    $arguments = @('-Xms1g','-Xmx4g','--add-modules=jdk.incubator.vector','-Djvector.vectorization_provider=panama','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-cp',$runtimeClasspath,'com.filteredann.planner.demo.PlannerCli','--root',$repository)
    if ($Synthetic) { $arguments += '--synthetic' }
    if ($Interactive) { $arguments += '--interactive' }
    if ($Filter) { $arguments += @('--filter',$Filter,'--query-id',"$QueryId",'--recall',$Recall.ToString([Globalization.CultureInfo]::InvariantCulture)) }
    if ($MaxBudget -gt 0) { $arguments += @('--max-budget',"$MaxBudget") }
    if ($Cold) { $arguments += '--cold' }
    if ($NoGT) { $arguments += '--no-gt' }
    if ($Request) { $arguments += @('--request',$Request) }
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java.exe).Source }
    & $java @arguments
    if ($LASTEXITCODE -ne 0) { throw "Product exited with code $LASTEXITCODE" }
} finally { Pop-Location }
