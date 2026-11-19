param([switch]$Synthetic, [switch]$Build, [string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$product = Split-Path $PSScriptRoot -Parent
$output = if ($OutputDirectory) { [IO.Path]::GetFullPath($OutputDirectory) } else { Join-Path $product '.runs' }
New-Item -ItemType Directory -Force $output | Out-Null
$mode = if ($Synthetic) { 'synthetic' } else { 'sift' }
$runId = (Get-Date -Format 'yyyyMMddTHHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$log = Join-Path $output "$mode-$runId.txt"
& "$product/run.ps1" -Build:$Build -Synthetic:$Synthetic 2>&1 | Tee-Object -FilePath $log
if ($LASTEXITCODE -ne 0) { throw 'Demo failed.' }
$responses = @(Get-Content -LiteralPath $log | Where-Object { $_.StartsWith('RESPONSE_JSON ') } | ForEach-Object { $_.Substring(14) | ConvertFrom-Json })
if ($responses.Count -ne 7) { throw "Expected seven queries, received $($responses.Count)." }
$responses | ConvertTo-Json -Depth 40 | Set-Content (Join-Path $output "$mode-$runId.json") -Encoding utf8
Write-Host ''
Write-Host 'Summary (observed single queries, not benchmark percentiles):'
$responses | ForEach-Object {
    [PSCustomObject]@{
        Filter = $_.decision.signature
        Matching = ('{0:P3}' -f $_.decision.selectivity)
        Target = $_.decision.targetRecall
        Planned = "$($_.decision.strategy)/$($_.decision.budget)"
        Executed = "$($_.selectedStrategy)/$($_.selectedBudget)"
        GT = ($null -ne $_.actualRecall)
        ActualRecall = $_.actualRecall
        LatencyMs = [Math]::Round($_.queryLatencyNanos / 1e6, 3)
        Fallback = ($_.decision.safeFallback -or $_.executionFallback)
    }
} | Format-Table -AutoSize | Out-Host
Write-Host "Seven real queries completed. Records: $log"
