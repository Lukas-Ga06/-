# ============================================================================
# Flyway repair helper launcher (batch10 schema single-authority).
# ASCII only on purpose: Windows PowerShell 5.1 decodes BOM-less UTF-8 as ANSI.
#
# Usage examples (from D:\travel-agent):
#   # 1) check whether an existing database needs a checksum repair
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\.tools\flyway-repair\run.ps1 `
#       -Command validate -JdbcUrl "jdbc:mysql://127.0.0.1:3306/travel_agent_b10_upgrade" `
#       -DbUser root -DbPassword 123456 `
#       -Locations "filesystem:D:/travel-agent/travel-agent-server/src/main/resources/db/migration,filesystem:D:/travel-agent/travel-agent-server/src/main/resources/db/migration-mysql"
#
#   # 2) realign flyway_schema_history after a comment-only script fix (no DDL, no data change)
#   ... -Command repair ...
#
# Jars are taken from the local Maven repository (same versions as the app's dependency tree).
# ============================================================================
param(
    [Parameter(Mandatory = $true)][string]$Command,
    [Parameter(Mandatory = $true)][string]$JdbcUrl,
    [string]$DbUser = "root",
    [string]$DbPassword = "",
    [Parameter(Mandatory = $true)][string]$Locations,
    [string]$Target = ""
)

$ErrorActionPreference = "Stop"
$repo = Join-Path $env:USERPROFILE ".m2\repository"
$jars = @(
    (Join-Path $repo "org\flywaydb\flyway-core\11.7.2\flyway-core-11.7.2.jar"),
    (Join-Path $repo "org\flywaydb\flyway-mysql\11.7.2\flyway-mysql-11.7.2.jar"),
    (Join-Path $repo "com\google\code\gson\gson\2.10.1\gson-2.10.1.jar"),
    (Join-Path $repo "com\fasterxml\jackson\core\jackson-databind\2.19.2\jackson-databind-2.19.2.jar"),
    (Join-Path $repo "com\fasterxml\jackson\core\jackson-core\2.19.2\jackson-core-2.19.2.jar"),
    (Join-Path $repo "com\fasterxml\jackson\core\jackson-annotations\2.19.2\jackson-annotations-2.19.2.jar"),
    (Join-Path $repo "com\fasterxml\jackson\dataformat\jackson-dataformat-toml\2.19.2\jackson-dataformat-toml-2.19.2.jar"),
    (Join-Path $repo "org\slf4j\slf4j-api\2.0.17\slf4j-api-2.0.17.jar"),
    (Join-Path $repo "com\mysql\mysql-connector-j\9.4.0\mysql-connector-j-9.4.0.jar"),
    (Join-Path $repo "com\h2database\h2\2.3.232\h2-2.3.232.jar")
)

foreach ($jar in $jars) {
    if (-not (Test-Path -LiteralPath $jar)) {
        Write-Error "missing jar: $jar"
        exit 3
    }
}

$javaCmd = Get-Command java.exe -ErrorAction SilentlyContinue
if (-not $javaCmd) {
    Write-Error "java.exe not found in PATH"
    exit 3
}

$source = Join-Path $PSScriptRoot "FlywayRepair.java"
$classpath = $jars -join ";"
$javaArgs = @("-cp", $classpath, $source, $Command, $JdbcUrl, $DbUser, $DbPassword, $Locations)
if ($Target -ne "") { $javaArgs += $Target }

& $javaCmd.Source @javaArgs
exit $LASTEXITCODE