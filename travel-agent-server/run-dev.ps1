# 本地开发启动脚本：从 .env.local 读取密钥并注入环境变量后启动后端。
#
#   cd travel-agent-server
#   .\run-dev.ps1            # 前台启动（Ctrl+C 停止）
#   .\run-dev.ps1 -Jar       # 用已打包的 jar 启动
#
# .env.local 已被 .gitignore 忽略，格式为 KEY=VALUE 每行一条，例如：
#   LLM_API_KEY=sk-xxx
#   AMAP_KEY=xxx
#   JWT_SECRET=xxx

param(
    [switch]$Jar
)

$envFile = Join-Path $PSScriptRoot '.env.local'
if (Test-Path -LiteralPath $envFile) {
    Get-Content -LiteralPath $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { return }
        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { return }
        $name = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim()
        Set-Item -Path ("Env:" + $name) -Value $value
        Write-Host ("已加载 {0}（长度 {1}）" -f $name, $value.Length)
    }
} else {
    Write-Host "未找到 .env.local，将以默认配置启动（规则引擎模式）"
}

# 未显式配置 JWT_SECRET 时：生成一次并写入 .env.local，之后重启不会被强制重新登录
if (-not $env:JWT_SECRET) {
    $bytes = New-Object byte[] 48
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $env:JWT_SECRET = [Convert]::ToBase64String($bytes)
    Add-Content -LiteralPath $envFile -Value ("JWT_SECRET=" + $env:JWT_SECRET)
    Write-Host "已生成开发用 JWT_SECRET 并写入 .env.local（重启后登录状态保持）"
}

if ($Jar) {
    java -jar (Join-Path $PSScriptRoot 'target\travel-agent-server-0.1.0.jar')
} else {
    mvn -B spring-boot:run
}
