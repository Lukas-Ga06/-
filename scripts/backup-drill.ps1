#Requires -Version 5.1
<#
.SYNOPSIS
    travel-agent MySQL 备份/恢复演练脚本（dump → 可选恢复到临时库 → 关键表计数对比）。

.DESCRIPTION
    只做“备份 + 恢复到临时库 + 校验”，默认不触碰源库结构、不 DROP 任何已有库。
    - 默认只 dump；传 -RestoreDb 才恢复到一个独立临时库并对比行数。
    - 密码通过临时 defaults-extra-file 传递，不出现在命令行与日志；文件结束即删除。
    - 没有 mysql / mysqldump 客户端时以退出码 2 结束并给出明确错误，不产生“假 PASS”。
    - 退出码：0 = PASS；1 = FAIL（dump/恢复/校验失败）；2 = 环境或参数错误。

.NOTES
    文档：docs/operations/backup-restore.md
    参数名用 $DbHost / $DbUser，避免与 PowerShell 自动只读变量 $Host 冲突；
    -Host / -User 通过 Alias 绑定到这两个参数，调用方仍可按任务约定传 -Host / -User。
    注意：-Password 会进入调用方的命令历史；生产建议省略该参数、改用交互输入或受控凭据注入。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [Alias('Host')]
    [string]$DbHost,

    [int]$Port = 3306,

    [Parameter(Mandatory = $true)]
    [Alias('User')]
    [string]$DbUser,

    [string]$Password = '',

    [string]$Database = 'travel_agent',

    [string]$OutDir = (Join-Path (Get-Location) 'backups\drill'),

    [string]$RestoreDb = '',

    [string]$MysqlBin = '',

    [string]$MysqldumpBin = '',

    [string]$ReportPath = '',

    [switch]$CleanupRestoreDb,

    [switch]$DryRun
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$script:TempFiles = New-Object System.Collections.ArrayList
$script:Failures = New-Object System.Collections.ArrayList
$script:Evidence = New-Object System.Collections.ArrayList
$script:DefaultsFile = $null

# 关键表：与 docs/operations/backup-restore.md §6 保持一致
$script:KeyTables = @(
    'users', 'trip', 'itinerary_item', 'trip_photo', 'expense',
    'schedule_item', 'memo', 'conversation_message', 'conversation_meta',
    'pending_plan', 'plan_execution', 'agent_run', 'agent_task',
    'tool_call_trace', 'llm_quota_usage', 'rag_document',
    'rag_document_version', 'rag_chunk', 'trip_event', 'operation_history',
    'agent_command_dedupe'
)

function Write-Step {
    param([string]$Message)
    Write-Host ("[backup-drill] {0}" -f $Message)
}

function Clear-TempFiles {
    foreach ($path in $script:TempFiles) {
        try {
            if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
        } catch {
            Write-Host ("[backup-drill] WARN: 临时文件删除失败，请手工清理：{0}" -f $path)
        }
    }
    if ($null -ne $script:DefaultsFile -and (Test-Path -LiteralPath $script:DefaultsFile)) {
        try {
            Remove-Item -LiteralPath $script:DefaultsFile -Force
        } catch {
            Write-Host ("[backup-drill] WARN: 临时口令文件删除失败，请手工清理：{0}" -f $script:DefaultsFile)
        }
    }
}

function Fail {
    param([string]$Message, [int]$Code = 1)
    Write-Host ("[backup-drill] FAIL: {0}" -f $Message) -ForegroundColor Red
    Clear-TempFiles
    exit $Code
}

function Read-PlainText {
    param([string]$Text)
    if ($null -eq $Text) { return '' }
    return [string]$Text
}

function Resolve-Tool {
    param([string]$Explicit, [string]$Name)
    if (-not [string]::IsNullOrWhiteSpace($Explicit)) {
        if (-not (Test-Path -LiteralPath $Explicit)) {
            Fail "指定的 $Name 不存在：$Explicit" 2
        }
        return (Resolve-Path -LiteralPath $Explicit).Path
    }
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $cmd) {
        Fail "未找到 $Name。请安装 MySQL 8.4 客户端（mysql/mysqldump）或通过 -MysqlBin / -MysqldumpBin 指定完整路径。脚本不会在缺少客户端时伪造通过。" 2
    }
    return $cmd.Source
}

function Assert-SafeIdentifier {
    param([string]$Value, [string]$Name)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z0-9_]+$') {
        Fail "$Name 只允许字母、数字、下划线：'$Value'" 2
    }
}

function New-DefaultsFile {
    param([string]$UserName, [string]$Pass, [string]$HostName, [int]$PortNumber)
    $path = Join-Path $env:TEMP ("travel-agent-drill-{0}.cnf" -f ([guid]::NewGuid().ToString('N')))
    $content = @(
        '[client]',
        ("user={0}" -f $UserName),
        ("password={0}" -f $Pass),
        ("host={0}" -f $HostName),
        ("port={0}" -f $PortNumber)
    ) -join "`r`n"
    # UTF-8 无 BOM 写入：BOM 会破坏 defaults-extra-file 的 [client] 首行
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $content, $utf8NoBom)
    try {
        $acl = Get-Acl -LiteralPath $path
        $acl.SetAccessRuleProtection($true, $false)
        $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
            [System.Security.Principal.WindowsIdentity]::GetCurrent().Name,
            'Read,Write', 'Allow')
        $acl.SetAccessRule($rule)
        Set-Acl -LiteralPath $path -AclObject $acl
    } catch {
        Write-Step "WARN: 无法收紧临时口令文件 ACL（$($_.Exception.Message)），请确认该临时目录不共享。"
    }
    $null = $script:TempFiles.Add($path)
    return $path
}

function Invoke-MysqlScalar {
    param([string]$Mysql, [string]$DefaultsFile, [string]$Sql)
    # SQL 经 stdin 文件传入：避免 PowerShell 5.1 对含空格/引号的参数重新拼接，
    # 也避免原生命令 stderr 在 $ErrorActionPreference=Stop 下被当成终止错误。
    $sqlFile = Join-Path $env:TEMP ("travel-agent-drill-sql-{0}.sql" -f ([guid]::NewGuid().ToString('N')))
    $tmpOut = Join-Path $env:TEMP ("travel-agent-drill-out-{0}.txt" -f ([guid]::NewGuid().ToString('N')))
    $tmpErr = Join-Path $env:TEMP ("travel-agent-drill-err-{0}.txt" -f ([guid]::NewGuid().ToString('N')))
    $null = $script:TempFiles.Add($sqlFile)
    $null = $script:TempFiles.Add($tmpOut)
    $null = $script:TempFiles.Add($tmpErr)
    $null = Set-Content -LiteralPath $sqlFile -Value $Sql -Encoding ASCII
    $cliArgs = @(
        ("--defaults-extra-file=`"{0}`"" -f $DefaultsFile),
        '--batch', '--skip-column-names',
        ("--host={0}" -f $DbHost),
        ("--port={0}" -f $Port),
        ("--user={0}" -f $DbUser)
    )
    $proc = Start-Process -FilePath $Mysql -ArgumentList $cliArgs -NoNewWindow -Wait -PassThru `
        -RedirectStandardInput $sqlFile -RedirectStandardOutput $tmpOut -RedirectStandardError $tmpErr
    if ($proc.ExitCode -ne 0) {
        $errText = ''
        if (Test-Path -LiteralPath $tmpErr) { $errText = Read-PlainText (Get-Content -LiteralPath $tmpErr -Raw) }
        Fail ("mysql 执行失败（exit={0}）：{1}" -f $proc.ExitCode, $errText.Trim()) 1
    }
    $value = ''
    if (Test-Path -LiteralPath $tmpOut) { $value = Read-PlainText (Get-Content -LiteralPath $tmpOut -Raw) }
    return $value.Trim()
}

function Test-TableExists {
    param([string]$Mysql, [string]$DefaultsFile, [string]$DbName, [string]$TableName)
    $sql = ("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='{0}' AND table_name='{1}';" -f $DbName, $TableName)
    $value = Invoke-MysqlScalar -Mysql $Mysql -DefaultsFile $DefaultsFile -Sql $sql
    return ([int]$value -gt 0)
}

function Get-TableCount {
    param([string]$Mysql, [string]$DefaultsFile, [string]$DbName, [string]$TableName)
    $sql = ('SELECT COUNT(*) FROM `{0}`.`{1}`;' -f $DbName, $TableName)
    return [int](Invoke-MysqlScalar -Mysql $Mysql -DefaultsFile $DefaultsFile -Sql $sql)
}

function Get-FlywaySummary {
    param([string]$Mysql, [string]$DefaultsFile, [string]$DbName)
    $sql = ('SELECT CONCAT(COALESCE(SUM(CASE WHEN success=1 THEN 1 ELSE 0 END), 0), ''|'', COALESCE(MAX(CASE WHEN success=1 THEN version END), ''none''), ''|'', COALESCE(SUM(CASE WHEN success=0 THEN 1 ELSE 0 END), 0)) FROM `{0}`.`flyway_schema_history`;' -f $DbName)
    $raw = Invoke-MysqlScalar -Mysql $Mysql -DefaultsFile $DefaultsFile -Sql $sql
    $parts = $raw.Split('|')
    if ($parts.Count -lt 3) {
        Fail "无法解析源库 flyway_schema_history 汇总：'$raw'" 1
    }
    return [pscustomobject]@{
        Applied    = [int]$parts[0]
        MaxVersion = $parts[1]
        Failed     = [int]$parts[2]
    }
}

function Invoke-Restore {
    param([string]$Mysql, [string]$DefaultsFile, [string]$DbName, [string]$DumpPath)
    $tmpOut = Join-Path $env:TEMP ("travel-agent-drill-restore-out-{0}.txt" -f ([guid]::NewGuid().ToString('N')))
    $tmpErr = Join-Path $env:TEMP ("travel-agent-drill-restore-err-{0}.txt" -f ([guid]::NewGuid().ToString('N')))
    $null = $script:TempFiles.Add($tmpOut)
    $null = $script:TempFiles.Add($tmpErr)
    $cliArgs = @(
        ("--defaults-extra-file=`"{0}`"" -f $DefaultsFile),
        '--batch', '--skip-column-names', '--default-character-set=utf8mb4',
        ("--host={0}" -f $DbHost),
        ("--port={0}" -f $Port),
        ("--user={0}" -f $DbUser),
        $DbName
    )
    $proc = Start-Process -FilePath $Mysql -ArgumentList $cliArgs -NoNewWindow -Wait -PassThru `
        -RedirectStandardInput $DumpPath -RedirectStandardOutput $tmpOut -RedirectStandardError $tmpErr
    $errText = ''
    if (Test-Path -LiteralPath $tmpErr) { $errText = Read-PlainText (Get-Content -LiteralPath $tmpErr -Raw) }
    return [pscustomobject]@{ ExitCode = $proc.ExitCode; Error = $errText.Trim() }
}

# ---------------------------------------------------------------------------
# 参数与环境检查
# ---------------------------------------------------------------------------
Assert-SafeIdentifier -Value $Database -Name 'Database'
if (-not [string]::IsNullOrWhiteSpace($RestoreDb)) {
    Assert-SafeIdentifier -Value $RestoreDb -Name 'RestoreDb'
    if ($RestoreDb -eq $Database) {
        Fail "RestoreDb 不能与源库同名（默认约定：只恢复到独立临时库，避免覆盖生产数据）。" 2
    }
}

$mysql = Resolve-Tool -Explicit $MysqlBin -Name 'mysql'
$mysqldump = Resolve-Tool -Explicit $MysqldumpBin -Name 'mysqldump'

if ($DryRun) {
    Write-Step "DRY-RUN：不会连接数据库，也不会写入备份文件。"
    Write-Step ("mysql      = {0}" -f $mysql)
    Write-Step ("mysqldump  = {0}" -f $mysqldump)
    Write-Step ("target     = {0}:{1}/{2}" -f $DbHost, $Port, $Database)
    Write-Step ("outDir     = {0}" -f $OutDir)
    if ([string]::IsNullOrWhiteSpace($RestoreDb)) {
        Write-Step "mode       = 仅备份（不恢复）"
    } else {
        Write-Step ("mode       = 备份 + 恢复到临时库 {0} 并对比关键表行数" -f $RestoreDb)
    }
    exit 0
}

if ([string]::IsNullOrWhiteSpace($Password)) {
    if (-not [Environment]::UserInteractive) {
        Fail "未提供 -Password，且当前会话不可交互。请用受控方式传入口令。" 2
    }
    $secure = Read-Host -AsSecureString ("请输入 MySQL 用户 {0} 的口令（不回显、不写入日志）" -f $DbUser)
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $Password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

if (-not (Test-Path -LiteralPath $OutDir)) {
    $null = New-Item -ItemType Directory -Path $OutDir -Force
}
$OutDir = (Resolve-Path -LiteralPath $OutDir).Path

$script:DefaultsFile = New-DefaultsFile -UserName $DbUser -Pass $Password -HostName $DbHost -PortNumber $Port
$defaultsFile = $script:DefaultsFile

try {
    $serverVersion = Invoke-MysqlScalar -Mysql $mysql -DefaultsFile $defaultsFile -Sql 'SELECT VERSION();'
    Write-Step ("已连接 MySQL {0} @ {1}:{2}/{3}" -f $serverVersion, $DbHost, $Port, $Database)

    # ---------------- 1) dump ----------------
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $dumpPath = Join-Path $OutDir ("{0}-{1}.sql" -f $Database, $stamp)
    Write-Step ("开始备份 → {0}" -f $dumpPath)
    $dumpWatch = [System.Diagnostics.Stopwatch]::StartNew()
    $dumpArgs = @(
        ("--defaults-extra-file=`"{0}`"" -f $defaultsFile),
        '--single-transaction', '--routines', '--triggers',
        '--default-character-set=utf8mb4', '--no-tablespaces',
        ("--host={0}" -f $DbHost),
        ("--port={0}" -f $Port),
        ("--user={0}" -f $DbUser),
        ("--result-file={0}" -f $dumpPath),
        $Database
    )
    $dumpErr = Join-Path $env:TEMP ("travel-agent-drill-dump-err-{0}.txt" -f ([guid]::NewGuid().ToString('N')))
    $dumpOut = Join-Path $env:TEMP ("travel-agent-drill-dump-out-{0}.txt" -f ([guid]::NewGuid().ToString('N')))
    $null = $script:TempFiles.Add($dumpErr)
    $null = $script:TempFiles.Add($dumpOut)
    $dumpProc = Start-Process -FilePath $mysqldump -ArgumentList $dumpArgs -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $dumpOut -RedirectStandardError $dumpErr
    $dumpExit = $dumpProc.ExitCode
    $dumpWatch.Stop()
    if ($dumpExit -ne 0) {
        $dumpErrText = ''
        if (Test-Path -LiteralPath $dumpErr) { $dumpErrText = Read-PlainText (Get-Content -LiteralPath $dumpErr -Raw) }
        Fail ("mysqldump 失败（exit={0}）：{1}。未生成可用备份，不做恢复。" -f $dumpExit, $dumpErrText.Trim()) 1
    }
    if (-not (Test-Path -LiteralPath $dumpPath)) {
        Fail "mysqldump 退出码为 0，但未找到备份文件：$dumpPath" 1
    }
    $dumpFile = Get-Item -LiteralPath $dumpPath
    if ($dumpFile.Length -le 0) {
        Fail "备份文件为空：$dumpPath" 1
    }
    $sha = (Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash
    $shaPath = "$dumpPath.sha256"
    $null = Set-Content -LiteralPath $shaPath -Value ("{0}  {1}" -f $sha, [System.IO.Path]::GetFileName($dumpPath)) -Encoding ASCII
    $null = $script:Evidence.Add([pscustomobject]@{
        Kind = 'dump'; Path = $dumpPath; Bytes = $dumpFile.Length; Sha256 = $sha
        Seconds = [math]::Round($dumpWatch.Elapsed.TotalSeconds, 2)
    })
    Write-Step ("备份完成：{0:N0} 字节，sha256={1}，耗时 {2:N2}s" -f $dumpFile.Length, $sha, $dumpWatch.Elapsed.TotalSeconds)

    $sourceFlyway = Get-FlywaySummary -Mysql $mysql -DefaultsFile $defaultsFile -DbName $Database
    Write-Step ("源库 Flyway：applied={0}, maxVersion={1}, failed={2}" -f $sourceFlyway.Applied, $sourceFlyway.MaxVersion, $sourceFlyway.Failed)

    if ([string]::IsNullOrWhiteSpace($RestoreDb)) {
        Write-Step "未指定 -RestoreDb：本次只做备份，不执行恢复与对比。"
        $result = 'PASS'
        $exitCode = 0
    } else {
        # ---------------- 2) 创建临时库 ----------------
        Write-Step ("创建临时库（若不存在）：{0}" -f $RestoreDb)
        $createSql = ('CREATE DATABASE IF NOT EXISTS `{0}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;' -f $RestoreDb)
        $null = Invoke-MysqlScalar -Mysql $mysql -DefaultsFile $defaultsFile -Sql $createSql

        # ---------------- 3) 恢复 ----------------
        Write-Step ("开始恢复 {0} → {1}" -f $dumpPath, $RestoreDb)
        $restoreWatch = [System.Diagnostics.Stopwatch]::StartNew()
        $restore = Invoke-Restore -Mysql $mysql -DefaultsFile $defaultsFile -DbName $RestoreDb -DumpPath $dumpPath
        $restoreWatch.Stop()
        if ($restore.ExitCode -ne 0) {
            Fail ("恢复失败（exit={0}）：{1}" -f $restore.ExitCode, $restore.Error) 1
        }
        $null = $script:Evidence.Add([pscustomobject]@{
            Kind = 'restore'; Database = $RestoreDb; Seconds = [math]::Round($restoreWatch.Elapsed.TotalSeconds, 2)
        })
        Write-Step ("恢复完成，耗时 {0:N2}s" -f $restoreWatch.Elapsed.TotalSeconds)

        # ---------------- 4) 对比 ----------------
        $restoreFlyway = Get-FlywaySummary -Mysql $mysql -DefaultsFile $defaultsFile -DbName $RestoreDb
        if ($restoreFlyway.Applied -ne $sourceFlyway.Applied) {
            $null = $script:Failures.Add("flyway_schema_history 行数不一致：源 $($sourceFlyway.Applied) / 恢复 $($restoreFlyway.Applied)")
        }
        if ("$($restoreFlyway.MaxVersion)" -ne "$($sourceFlyway.MaxVersion)") {
            $null = $script:Failures.Add("Flyway 最大版本不一致：源 $($sourceFlyway.MaxVersion) / 恢复 $($restoreFlyway.MaxVersion)")
        }
        if ($restoreFlyway.Failed -ne 0) {
            $null = $script:Failures.Add("恢复库存在失败迁移记录：$($restoreFlyway.Failed) 行")
        }

        $rows = New-Object System.Collections.ArrayList
        foreach ($table in $script:KeyTables) {
            $exists = Test-TableExists -Mysql $mysql -DefaultsFile $defaultsFile -DbName $Database -TableName $table
            if (-not $exists) {
                Write-Step ("SKIP 表 {0}：源库不存在" -f $table)
                continue
            }
            $sourceCount = Get-TableCount -Mysql $mysql -DefaultsFile $defaultsFile -DbName $Database -TableName $table
            $restoredCount = Get-TableCount -Mysql $mysql -DefaultsFile $defaultsFile -DbName $RestoreDb -TableName $table
            $match = ($sourceCount -eq $restoredCount)
            $null = $rows.Add([pscustomobject]@{ Table = $table; Source = $sourceCount; Restored = $restoredCount; Match = $match })
            if (-not $match) {
                $null = $script:Failures.Add("表 $table 行数不一致：源 $sourceCount / 恢复 $restoredCount")
            }
        }
        $null = $script:Evidence.Add([pscustomobject]@{ Kind = 'counts'; Rows = $rows })

        Write-Host ''
        Write-Host '关键表行数对比：'
        $rows | Format-Table -AutoSize | Out-String | Write-Host

        if ($script:Failures.Count -gt 0) {
            $result = 'FAIL'
            $exitCode = 1
        } else {
            $result = 'PASS'
            $exitCode = 0
        }

        if ($CleanupRestoreDb) {
            Write-Step ("清理临时库：{0}" -f $RestoreDb)
            $null = Invoke-MysqlScalar -Mysql $mysql -DefaultsFile $defaultsFile -Sql ('DROP DATABASE IF EXISTS `{0}`;' -f $RestoreDb)
        } else {
            Write-Step ("临时库保留（默认不执行破坏性操作）：{0}；确认后可手工 DROP。" -f $RestoreDb)
        }
    }

    # ---------------- 5) 汇总 ----------------
    $report = [pscustomobject]@{
        generatedAt = (Get-Date).ToString('o')
        target      = [pscustomobject]@{ host = $DbHost; port = $Port; database = $Database }
        server      = $serverVersion
        restoreDb   = $RestoreDb
        dump        = ($script:Evidence | Where-Object { $_.Kind -eq 'dump' })
        restore     = ($script:Evidence | Where-Object { $_.Kind -eq 'restore' })
        counts      = ($script:Evidence | Where-Object { $_.Kind -eq 'counts' })
        failures    = $script:Failures
        result      = $result
    }
    if (-not [string]::IsNullOrWhiteSpace($ReportPath)) {
        $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
        Write-Step ("报告已写入：{0}" -f $ReportPath)
    }

    Write-Host ''
    if ($result -eq 'PASS') {
        $modeText = ''
        if (-not [string]::IsNullOrWhiteSpace($RestoreDb)) { $modeText = '+恢复+行数对比' }
        Write-Host ("[backup-drill] PASS：备份{0}完成。" -f $modeText) -ForegroundColor Green
    } else {
        Write-Host '[backup-drill] FAIL：存在不一致项。' -ForegroundColor Red
        foreach ($f in $script:Failures) { Write-Host ("  - {0}" -f $f) -ForegroundColor Red }
    }
    Clear-TempFiles
    exit $exitCode
} catch {
    Fail ("未预期错误：{0}" -f $_.Exception.Message) 1
}