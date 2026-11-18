<#
.SYNOPSIS
  恢复演练后清理：停掉演练端口的独立实例并删除演练库。

.DESCRIPTION
  两步清理，均带护栏：
    1. 停实例：查占用 -Port 的监听进程，仅当进程名为 java 时才允许结束；
       端口为 8080（主实例）时直接拒绝，防误伤在跑的服务。
    2. 删演练库：DROP 指定数据库；目标为系统库或 yilian 主库时拒绝。

  退出码：0 成功（或无可清理项）；2 参数/护栏拒绝；3 停进程失败；4 drop 失败。

  凭据纪律：密码按 -Passwd > 环境变量 YILIAN_DB_PASSWORD > MYSQL_PWD 的顺序
  取用，全部缺失时报错打印用法（仅 -KeepDb 跳过删库时可不提供）。

.EXAMPLE
  powershell -File scripts/drill-cleanup.ps1 -Passwd '***'
.EXAMPLE
  powershell -File scripts/drill-cleanup.ps1 -Port 8081 -DrillDb yilian_drill -KeepDb -Passwd '***'
#>
[CmdletBinding()]
param(
    # 演练实例监听端口；8080 为主实例端口，脚本拒绝处理
    [int]$Port = 8081,
    # 演练库名；不用 -Db（与公共参数 Debug 的别名 db 冲突）
    [string]$DrillDb = 'yilian_drill',
    # 跳过删库，只停实例
    [switch]$KeepDb,
    # MySQL 用户名
    [string]$U = 'root',
    # MySQL 端口
    [int]$MysqlPort = 3306,
    # MySQL 主机
    [string]$DbHost = 'localhost',
    # 数据库密码；缺省回落环境变量，不设硬编码默认值
    [string]$Passwd = ''
)

$ErrorActionPreference = 'Stop'

function Show-Usage {
    Write-Host '用法: powershell -File scripts/drill-cleanup.ps1 [-Port 8081] [-DrillDb yilian_drill] [-KeepDb] [-U root] [-MysqlPort 3306] [-DbHost localhost] [-Passwd ***]'
}

function Fail {
    param([int]$ExitCode, [string]$Message)
    [Console]::Error.WriteLine($Message)
    exit $ExitCode
}

# 主实例端口护栏：清理动作永远不允许触碰 8080
if ($Port -eq 8080) {
    Fail 2 '拒绝处理端口 8080（主实例）。演练清理只允许非 8080 端口。'
}
if ($DrillDb -notmatch '^[A-Za-z0-9_]+$') {
    Show-Usage
    Fail 2 ('非法库名: ' + $DrillDb)
}
$systemDbs = @('information_schema', 'mysql', 'performance_schema', 'sys')
if ($systemDbs -contains $DrillDb.ToLowerInvariant() -or $DrillDb -ieq 'yilian') {
    Fail 2 ('拒绝删除库: ' + $DrillDb + '（系统库与主库不在清理范围）。')
}

# --- 第一步：停演练实例（只结束 java 进程，避免误杀其他占端口的程序） ---
$listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
if ($listeners.Count -eq 0) {
    Write-Host ('端口 ' + $Port + ' 无监听进程，跳过停实例。')
} else {
    $pids = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
    foreach ($procId in $pids) {
        $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if ($null -eq $proc) { continue }
        if ($proc.ProcessName -ne 'java') {
            Fail 2 ('端口 ' + $Port + ' 被 ' + $proc.ProcessName + '（PID ' + $procId + '）占用，非 java 进程，拒绝结束；请人工确认。')
        }
        Stop-Process -Id $procId -Force -ErrorAction Stop
        Write-Host ('已结束演练实例: PID ' + $procId + '（端口 ' + $Port + '）')
    }
}

# --- 第二步：删除演练库 ---
if ($KeepDb) {
    Write-Host '-KeepDb 已指定，跳过删库。'
    exit 0
}

if ([string]::IsNullOrEmpty($Passwd)) {
    if (-not [string]::IsNullOrEmpty($env:YILIAN_DB_PASSWORD)) {
        $Passwd = $env:YILIAN_DB_PASSWORD
    } elseif (-not [string]::IsNullOrEmpty($env:MYSQL_PWD)) {
        $Passwd = $env:MYSQL_PWD
    } else {
        Show-Usage
        Fail 2 '缺少数据库密码：请用 -Passwd 或环境变量 YILIAN_DB_PASSWORD（或 MYSQL_PWD）提供；脚本不内置默认密码。可用 -KeepDb 只停实例不删库。'
    }
}

$mysql = (Get-Command mysql -ErrorAction SilentlyContinue).Source
if ([string]::IsNullOrEmpty($mysql)) {
    Fail 2 'mysql 客户端不在 PATH 中；请确认 MySQL 8.0 bin 目录已加入 PATH。'
}

$env:MYSQL_PWD = $Passwd
try {
    & $mysql ('--host=' + $DbHost) ('--port=' + $MysqlPort) ('--user=' + $U) -e ('DROP DATABASE IF EXISTS ' + $DrillDb + ';')
    if ($LASTEXITCODE -ne 0) {
        Fail 4 ('删除演练库失败: ' + $DrillDb)
    }
    Write-Host ('已删除演练库: ' + $DrillDb)
} finally {
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
}

exit 0
