# ============================================================================
# mysql-it.ps1 —— 本地一键跑「真实 MySQL Flyway 全量迁移 + Hibernate validate」
#
# 做的事：
#   1) 清理 24 小时前的一次性测试库 travel_agent_it_*（可用 -KeepOldDatabases 跳过）；
#   2) 导出 MYSQL_IT_* 环境变量；
#   3) 在 travel-agent-server/ 下执行：
#        mvn -B -Dtest=MySqlFlywayMigrationTest test
#      并把 Maven 退出码原样透传。
#
# 默认值面向本机便携 MySQL 8.4（127.0.0.1:3307 / root / 123456）。
# CI 或远程实例用参数覆盖，例如：
#   .\scripts\mysql-it.ps1 -DbHost 127.0.0.1 -DbPort 3306 -DbPassword root
#
# 依赖：MySQL 实例已启动；账号需要 CREATE DATABASE 权限；PATH 中有 mvn（JDK 17）。
# 编码：UTF-8（带 BOM，Windows PowerShell 5.1 才能正确读取中文）；换行 CRLF（.gitattributes）。
# ============================================================================

[CmdletBinding()]
param(
    [string]$DbHost = "127.0.0.1",
    [int]$DbPort = 3307,
    [string]$DbUser = "root",
    [string]$DbPassword = "123456",
    # 可选：mysql.exe 绝对路径；默认自动找仓库内置便携客户端或 PATH
    [string]$MysqlBin = "",
    # 可选：mysql 客户端 defaults-extra-file（给定时优先于 -DbUser/-DbPassword）
    [string]$MysqlDefaultsFile = "",
    # 只清理创建时间早于 N 小时的一次性库
    [int]$RetentionHours = 24,
    [switch]$KeepOldDatabases
)

$ErrorActionPreference = "Stop"

# 统一控制台输出为 UTF-8：Windows PowerShell 5.1 默认用 OEM 代码页，
# 中文提示在重定向日志/CI 里会变成乱码。无控制台句柄的宿主可能拒绝，忽略即可。
try {
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [Console]::OutputEncoding = $utf8NoBom
    $OutputEncoding = $utf8NoBom
} catch {
    # 不改编码不影响测试执行
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir
$ServerDir = Join-Path $RepoRoot "travel-agent-server"

if (-not (Test-Path -LiteralPath $ServerDir)) {
    Write-Error "找不到后端模块目录: $ServerDir"
    exit 1
}

# ----------------------------------------------------------------------------
# 1) 可选清理：DROP 早于 RetentionHours 的 travel_agent_it_*（只匹配测试库命名）
# ----------------------------------------------------------------------------
function Find-MysqlClient {
    param([string]$ExplicitPath, [string]$Root)
    if ($ExplicitPath) {
        if (-not (Test-Path -LiteralPath $ExplicitPath)) {
            throw "指定的 mysql 客户端不存在: $ExplicitPath"
        }
        return (Resolve-Path -LiteralPath $ExplicitPath).Path
    }
    $bundled = Join-Path $Root ".tools\mysql8410-3307\mysql-8.4.10-winx64\bin\mysql.exe"
    if (Test-Path -LiteralPath $bundled) {
        return $bundled
    }
    $command = Get-Command "mysql.exe" -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    return $null
}

function Invoke-Cleanup {
    $client = $null
    try {
        $client = Find-MysqlClient -ExplicitPath $MysqlBin -Root $RepoRoot
    } catch {
        Write-Warning "跳过旧库清理：$($_.Exception.Message)"
        return 0
    }
    if (-not $client) {
        Write-Warning "跳过旧库清理：未找到 mysql.exe（可用 -MysqlBin 指定）。测试本身不受影响。"
        return 0
    }
    if ($RetentionHours -lt 0) {
        Write-Warning "跳过旧库清理：RetentionHours 不能为负（$RetentionHours）。"
        return 0
    }

    $listSql = "SELECT table_schema FROM information_schema.tables " +
        "WHERE table_schema REGEXP '^travel_agent_it_[0-9]{14}(_[0-9a-f]{4})?$' " +
        "GROUP BY table_schema " +
        "HAVING MIN(create_time) < DATE_SUB(NOW(), INTERVAL $RetentionHours HOUR);"

    $mysqlArgs = @()
    $oldPwd = $env:MYSQL_PWD
    if ($MysqlDefaultsFile) {
        if (-not (Test-Path -LiteralPath $MysqlDefaultsFile)) {
            Write-Warning "跳过旧库清理：defaults-extra-file 不存在: $MysqlDefaultsFile"
            return 0
        }
        $mysqlArgs += "--defaults-extra-file=$MysqlDefaultsFile"
    } else {
        $mysqlArgs += @("--connect-timeout=5", "-h", $DbHost, "-P", "$DbPort", "-u", $DbUser)
        $env:MYSQL_PWD = $DbPassword
    }
    $mysqlArgs += @("-N", "-B", "-e", $listSql)

    try {
        $output = & $client @mysqlArgs 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "跳过旧库清理：查询失败（exit=$LASTEXITCODE）。若实例未启动，测试会给出更明确的错误。"
            Write-Warning ($output | Out-String).Trim()
            return 0
        }
    } catch {
        Write-Warning "跳过旧库清理：$($_.Exception.Message)"
        return 0
    } finally {
        if ($null -eq $oldPwd) {
            Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
        } else {
            $env:MYSQL_PWD = $oldPwd
        }
    }

    $names = @($output | ForEach-Object { ("$_" -replace "`r", "").Trim() } | Where-Object { $_ })
    $dropStatements = @()
    foreach ($name in $names) {
        # 双保险：只 DROP 严格符合测试库命名规则的库
        if ($name -match '^travel_agent_it_[0-9]{14}(_[0-9a-f]{4})?$') {
            $dropStatements += "DROP DATABASE IF EXISTS ``$name``;"
        } else {
            Write-Warning "忽略不符合命名规则的库: $name"
        }
    }
    if ($dropStatements.Count -eq 0) {
        Write-Host "[mysql-it] 没有超过 $RetentionHours 小时的旧测试库需要清理。"
        return 0
    }

    $dropSql = $dropStatements -join " "
    $dropArgs = @()
    $oldPwd = $env:MYSQL_PWD
    if ($MysqlDefaultsFile) {
        $dropArgs += "--defaults-extra-file=$MysqlDefaultsFile"
    } else {
        $dropArgs += @("--connect-timeout=5", "-h", $DbHost, "-P", "$DbPort", "-u", $DbUser)
        $env:MYSQL_PWD = $DbPassword
    }
    $dropArgs += @("-N", "-B", "-e", $dropSql)

    try {
        & $client @dropArgs 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "旧库清理失败（exit=$LASTEXITCODE），继续执行测试。"
            return 0
        }
        Write-Host "[mysql-it] 已清理 $($dropStatements.Count) 个超过 $RetentionHours 小时的旧测试库: $($names -join ', ')"
    } catch {
        Write-Warning "旧库清理失败：$($_.Exception.Message)，继续执行测试。"
    } finally {
        if ($null -eq $oldPwd) {
            Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
        } else {
            $env:MYSQL_PWD = $oldPwd
        }
    }
    return $dropStatements.Count
}

if ($KeepOldDatabases) {
    Write-Host "[mysql-it] 已按 -KeepOldDatabases 跳过旧库清理。"
} else {
    Invoke-Cleanup | Out-Null
}

# ----------------------------------------------------------------------------
# 2) 导出测试所需环境变量（只影响当前进程）
# ----------------------------------------------------------------------------
$env:MYSQL_IT = "true"
$env:MYSQL_IT_HOST = $DbHost
$env:MYSQL_IT_PORT = "$DbPort"
$env:MYSQL_IT_USERNAME = $DbUser
$env:MYSQL_IT_PASSWORD = $DbPassword

Write-Host "[mysql-it] MySQL=$DbHost`:$DbPort user=$DbUser"
Write-Host "[mysql-it] 运行: mvn -B -Dtest=MySqlFlywayMigrationTest test  (cwd=$ServerDir)"
Write-Host "[mysql-it] 测试会创建一次性库 travel_agent_it_<timestamp> 并保留作为证据。"
Write-Host ""

# ----------------------------------------------------------------------------
# 3) 运行测试并透传退出码
# ----------------------------------------------------------------------------
$exitCode = 1
Push-Location -LiteralPath $ServerDir
try {
    & mvn -B -Dtest=MySqlFlywayMigrationTest test
    $exitCode = $LASTEXITCODE
} catch {
    Write-Error "无法执行 mvn（确认 Maven 3.9+ 与 JDK 17 在 PATH 中）：$($_.Exception.Message)"
    $exitCode = 1
} finally {
    Pop-Location
}

Write-Host ""
if ($exitCode -eq 0) {
    Write-Host "[mysql-it] 通过（exit=0）。证据库保留；排障与证据查询见 docs/guide/mysql-migration.md"
} else {
    Write-Host "[mysql-it] 失败（exit=$exitCode）。排障步骤见 docs/guide/mysql-migration.md"
}
exit $exitCode