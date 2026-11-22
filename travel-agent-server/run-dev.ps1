<#
.SYNOPSIS
    本地开发启动脚本：加载 .env.local 的环境变量后启动后端。

.DESCRIPTION
    用法：
        .\run-dev.ps1             前台启动（Ctrl+C 停止）
        .\run-dev.ps1 -Jar        用已打包的 target\travel-agent-server-0.1.0.jar 启动
        .\run-dev.ps1 -Check      只做自检（加载变量、检查 java/mvn），不启动服务
        .\run-dev.ps1 -NoPause    结束后不暂停（脚本化调用时用）

    直接双击 run-dev.ps1 或 run-dev.cmd 也能跑：脚本会切到自身目录，
    并在退出/出错时暂停，窗口不会一闪而过。

    .env.local 已被 .gitignore 忽略，格式为每行一条 KEY=VALUE，例如：
        LLM_API_KEY=sk-xxx
        AMAP_KEY=xxx
        JWT_SECRET=xxx
    文件按 properties 语法逐行解析，Windows 路径请用正斜杠 /，反斜杠会被当作转义符。

.NOTES
    本文件必须保存为 UTF-8 with BOM：Windows PowerShell 5.1 在没有 BOM 时会按
    ANSI 读取，中文注释会被解析成乱码并直接报语法错误。
#>
[CmdletBinding()]
param(
    [switch]$Jar,
    [switch]$Check,
    [switch]$NoPause
)

# 保持默认的 Continue：java/mvn 会把版本信息写到 stderr，在 Stop 语义下
# （配合 2>&1）会被当成终止错误直接抛出。真正的失败由下面的显式检查和 exit code 判断。
$ErrorActionPreference = 'Continue'

# H2 文件库按相对路径 ./data 落盘，双击启动时工作目录不可控，先切到脚本所在目录
Set-Location -LiteralPath $PSScriptRoot

function Test-DoubleClicked {
    # 由资源管理器双击启动时，父进程是 explorer.exe
    try {
        $parentId = (Get-CimInstance Win32_Process -Filter "ProcessId=$PID").ParentProcessId
        return (Get-Process -Id $parentId -ErrorAction Stop).ProcessName -eq 'explorer'
    } catch {
        return $false
    }
}

$pauseAtExit = (-not $NoPause) -and (Test-DoubleClicked)

function Read-EnvFile {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        Write-Host '未找到 .env.local，将以默认配置启动（规则引擎模式）' -ForegroundColor Yellow
        return
    }

    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $line = $line.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { continue }
        $idx = $line.IndexOf('=')
        if ($idx -lt 1) { continue }
        $name = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim()
        Set-Item -Path ('Env:' + $name) -Value $value
        Write-Host ("  已加载 {0}（长度 {1}）" -f $name, $value.Length) -ForegroundColor DarkGray
    }
}

function Assert-Tool {
    param([string]$Name, [string]$Hint)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "未找到 $Name，请先安装并加入 PATH。$Hint"
    }
}

$exitCode = 1
try {
    Write-Host '== 加载 .env.local ==' -ForegroundColor Cyan
    $envFile = Join-Path $PSScriptRoot '.env.local'
    Read-EnvFile -Path $envFile

    # 未显式配置 JWT_SECRET 时：生成一次并写入 .env.local，之后重启不会被强制重新登录
    if (-not $env:JWT_SECRET) {
        $bytes = New-Object byte[] 48
        [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $env:JWT_SECRET = [Convert]::ToBase64String($bytes)
        Add-Content -LiteralPath $envFile -Value ('JWT_SECRET=' + $env:JWT_SECRET) -Encoding ASCII
        Write-Host '已生成开发用 JWT_SECRET 并写入 .env.local（重启后登录状态保持）' -ForegroundColor Yellow
    }

    Write-Host '== 检查依赖 ==' -ForegroundColor Cyan
    Assert-Tool -Name 'java' -Hint '需要 JDK 17+。'
    Assert-Tool -Name 'mvn' -Hint '需要 Maven 3.8+。'
    # 版本信息：java 把版本写到 stderr，直接用 2>&1 在 PowerShell 5.1 下会被包装成
    # ErrorRecord（刷红字），所以 Windows 上借 cmd 合并流后再读。
    if ($env:OS -eq 'Windows_NT') {
        $javaLine = ([string]((& cmd /c 'java -version 2>&1' | Select-Object -First 1))).Trim()
        $mvnLine = ([string]((& cmd /c 'mvn -v 2>&1' | Select-Object -First 1))).Trim()
    } else {
        $javaLine = ([string]((& java -version 2>&1 | Select-Object -First 1))).Trim()
        $mvnLine = ([string]((& mvn -v 2>&1 | Select-Object -First 1))).Trim()
    }
    Write-Host ('  ' + $javaLine) -ForegroundColor DarkGray
    Write-Host ('  ' + $mvnLine) -ForegroundColor DarkGray

    if ($Check) {
        Write-Host '== 自检通过（未启动服务）==' -ForegroundColor Green
        $exitCode = 0
    }
    elseif ($Jar) {
        $jar = Join-Path $PSScriptRoot 'target\travel-agent-server-0.1.0.jar'
        if (-not (Test-Path -LiteralPath $jar)) {
            throw "未找到 $jar，请先执行 mvn package。"
        }
        Write-Host "== 启动 jar：$jar ==" -ForegroundColor Cyan
        & java -jar $jar
        $exitCode = $LASTEXITCODE
    }
    else {
        Write-Host '== 启动 Spring Boot（mvn spring-boot:run）==' -ForegroundColor Cyan
        & mvn -B spring-boot:run
        $exitCode = $LASTEXITCODE
    }
} catch {
    Write-Host ('启动失败：' + $_.Exception.Message) -ForegroundColor Red
    $exitCode = 1
} finally {
    if ($pauseAtExit) {
        Read-Host '按回车键关闭窗口' | Out-Null
    }
}

exit $exitCode