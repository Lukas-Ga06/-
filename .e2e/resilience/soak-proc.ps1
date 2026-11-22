# 长跑采样器：每 5s 记录一次后端进程 RSS / 线程数 / H2 文件大小。
# 由 run-soak.mjs 配套使用；单独起一个后台作业跑，避免与请求循环互相阻塞。
# 用法：
#   pwsh -File soak-proc.ps1 -Out <csv> -TargetPid 44612 -DbPath <mv.db> -StopFlag <flag>
param(
  [Parameter(Mandatory = $true)][string]$Out,
  [Parameter(Mandatory = $true)][int]$TargetPid,
  [Parameter(Mandatory = $true)][string]$DbPath,
  [Parameter(Mandatory = $true)][string]$StopFlag
)

$ErrorActionPreference = 'Continue'
Set-Content -Path $Out -Value 'iso,epochMs,rssBytes,threads,dbBytes' -Encoding utf8

# 注意：必须显式指定 DateTimeKind=Utc，否则 ([datetime]'1970-01-01T00:00:00Z')
# 会按本地时区解释，epoch 列整体偏移一个时区（实测差 8 小时）。
$epochBase = [datetime]::new(1970, 1, 1, 0, 0, 0, [System.DateTimeKind]::Utc)

while (-not (Test-Path -LiteralPath $StopFlag)) {
  $proc = Get-Process -Id $TargetPid -ErrorAction SilentlyContinue
  if ($null -ne $proc) {
    $now = Get-Date
    $epochMs = [int64](($now.ToUniversalTime() - $epochBase).TotalMilliseconds)
    $dbBytes = 0
    if (Test-Path -LiteralPath $DbPath) {
      $dbBytes = [System.IO.FileInfo]::new($DbPath).Length
    }
    $line = '{0},{1},{2},{3},{4}' -f $now.ToString('o'), $epochMs, $proc.WorkingSet64, $proc.Threads.Count, $dbBytes
    Add-Content -Path $Out -Value $line -Encoding utf8
  }
  Start-Sleep -Seconds 5
}
Write-Output "sampler stopped: $(Get-Date -Format o)"