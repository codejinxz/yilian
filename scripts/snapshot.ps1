<#
.SYNOPSIS
  yilian 数据库全量逻辑快照（mysqldump）。

.DESCRIPTION
  生成单库一致性全量快照：
    --single-transaction            InnoDB 一致性读视图，备份期间业务写入不被阻塞
    --routines --triggers --events  存储过程/触发器/事件一并入库
    --source-data=2                 头部以注释记录 binlog 位点（PITR 重放起点；需服务端 binlog 开启）
    --set-gtid-purged=OFF           不携带 GTID 集合，快照可还原进任意已有实例
  产物：<OutputDir>/<yyyyMMdd-HHmmss>/<库名>-full.sql.gz。
  写入顺序：先落未压缩 .sql → 校验末行 footer（"-- Dump completed on ..."；
  被截断的 dump 缺该行）→ 校验通过才压缩为 .gz 并删除中间文件。
  保留策略：只清理目录名形如 yyyyMMdd-HHmmss 的历史快照目录，默认保留最近 14 份；
  清理失败仅告警，不影响本次快照的退出码。

  退出码：0 成功；2 用法/参数错误；3 mysqldump 失败；4 footer 校验失败；5 压缩失败。
  失败信息一律写 stderr 并以非零码退出。

  凭据纪律：密码按 -Passwd > 环境变量 YILIAN_DB_PASSWORD > MYSQL_PWD 的顺序取用，
  全部缺失时打印用法并以 2 退出；本脚本不内置任何默认密码。密码经 MYSQL_PWD
  环境变量传给客户端，不出现在命令行参数与日志里。

.EXAMPLE
  powershell -File scripts/snapshot.ps1 -Passwd '***'
.EXAMPLE
  powershell -File scripts/snapshot.ps1 -D yilian -Keep 30 -Passwd '***'
#>
[CmdletBinding()]
param(
    # 要备份的数据库名
    [string]$D = 'yilian',
    # MySQL 用户名
    [string]$U = 'root',
    # MySQL 端口
    [int]$P = 3306,
    # MySQL 主机
    [string]$DbHost = 'localhost',
    # 快照输出根目录；缺省为仓库内 server/data/snapshots（gitignored，快照含真实用户数据不入库）
    [string]$OutputDir = '',
    # 保留的历史快照份数
    [int]$Keep = 14,
    # 数据库密码；缺省回落环境变量，不设硬编码默认值
    [string]$Passwd = ''
)

$ErrorActionPreference = 'Stop'

function Show-Usage {
    Write-Host '用法: powershell -File scripts/snapshot.ps1 [-D yilian] [-U root] [-P 3306] [-DbHost localhost] [-OutputDir server/data/snapshots] [-Keep 14] [-Passwd ***]'
    Write-Host '密码来源（按优先级）: -Passwd 参数 > 环境变量 YILIAN_DB_PASSWORD > 环境变量 MYSQL_PWD；三者全缺时拒绝执行。'
}

function Fail {
    param([int]$ExitCode, [string]$Message)
    [Console]::Error.WriteLine($Message)
    exit $ExitCode
}

# 密码解析：任何默认值都不提供，缺失即报用法错误
if ([string]::IsNullOrEmpty($Passwd)) {
    if (-not [string]::IsNullOrEmpty($env:YILIAN_DB_PASSWORD)) {
        $Passwd = $env:YILIAN_DB_PASSWORD
    } elseif (-not [string]::IsNullOrEmpty($env:MYSQL_PWD)) {
        $Passwd = $env:MYSQL_PWD
    } else {
        Show-Usage
        Fail 2 '缺少数据库密码：请用 -Passwd 或环境变量 YILIAN_DB_PASSWORD（或 MYSQL_PWD）提供；脚本不内置默认密码。'
    }
}

# 库名只允许安全字符，避免拼进命令行时引入注入面
if ($D -notmatch '^[A-Za-z0-9_]+$') {
    Show-Usage
    Fail 2 ('非法数据库名: ' + $D)
}
if ($Keep -lt 1) {
    Show-Usage
    Fail 2 ('-Keep 必须 >= 1，当前值: ' + $Keep)
}

$mysqldump = (Get-Command mysqldump -ErrorAction SilentlyContinue).Source
if ([string]::IsNullOrEmpty($mysqldump)) {
    Show-Usage
    Fail 2 'mysqldump 不在 PATH 中；请确认 MySQL 8.0 bin 目录已加入 PATH。'
}

# 输出目录缺省落在仓库 server/data/snapshots（整体已被 .gitignore 覆盖）
if ([string]::IsNullOrEmpty($OutputDir)) {
    $repoRoot = Split-Path -Parent $PSScriptRoot
    $OutputDir = Join-Path $repoRoot 'server/data/snapshots'
}
if (-not [System.IO.Path]::IsPathRooted($OutputDir)) {
    $OutputDir = Join-Path (Get-Location).Path $OutputDir
}
$OutputDir = [System.IO.Path]::GetFullPath($OutputDir)

$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
$snapDir = Join-Path $OutputDir $ts
$rawFile = Join-Path $snapDir ($D + '-full.sql')
$gzFile = $rawFile + '.gz'

New-Item -ItemType Directory -Force -Path $snapDir | Out-Null

$swTotal = [System.Diagnostics.Stopwatch]::StartNew()

# 密码只经环境变量传给子进程，不进命令行
$env:MYSQL_PWD = $Passwd
try {
    $swDump = [System.Diagnostics.Stopwatch]::StartNew()
    $dumpArgs = @(
        ('--host=' + $DbHost),
        ('--port=' + $P),
        ('--user=' + $U),
        '--single-transaction',
        '--routines',
        '--triggers',
        '--events',
        '--source-data=2',
        '--set-gtid-purged=OFF',
        '--default-character-set=utf8mb4',
        ('--result-file=' + $rawFile),
        $D
    )
    & $mysqldump @dumpArgs
    if ($LASTEXITCODE -ne 0) {
        Fail 3 ('mysqldump 失败: 退出码 ' + $LASTEXITCODE + '，产物不完整，已终止。')
    }
    $swDump.Stop()

    # footer 校验：末行（忽略空行）必须是 "-- Dump completed on"，缺失视为 dump 被截断
    $tail = @(Get-Content -LiteralPath $rawFile -Tail 5)
    $lastLine = $null
    for ($i = $tail.Count - 1; $i -ge 0; $i--) {
        if ($tail[$i].Trim() -ne '') { $lastLine = $tail[$i]; break }
    }
    if ($null -eq $lastLine -or $lastLine -notmatch '-- Dump completed on') {
        Fail 4 ('dump footer 校验失败: 末行不是 "-- Dump completed on"，dump 可能被截断。末行=' + $lastLine)
    }

    # 在压缩前从文件头解析 binlog 位点注释，作为 PITR 的重放起点记账
    $binlogPos = '(未找到位点注释: 服务端 binlog 可能未开启)'
    $head = @(Get-Content -LiteralPath $rawFile -TotalCount 200)
    foreach ($line in $head) {
        if ($line -match 'CHANGE REPLICATION SOURCE TO') { $binlogPos = $line.Trim(); break }
        if ($line -match 'CHANGE MASTER TO') { $binlogPos = $line.Trim(); break }
    }
} finally {
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
}

# footer 校验通过后才压缩；先关压缩流（刷出 gzip 尾部）再关底层流
$swZip = [System.Diagnostics.Stopwatch]::StartNew()
try {
    Add-Type -AssemblyName System.IO.Compression
    $inStream = [System.IO.File]::OpenRead($rawFile)
    $outStream = [System.IO.File]::Create($gzFile)
    $gz = New-Object System.IO.Compression.GzipStream($outStream, [System.IO.Compression.CompressionLevel]::Optimal)
    try {
        $inStream.CopyTo($gz)
    } finally {
        $gz.Dispose()
        $outStream.Dispose()
        $inStream.Dispose()
    }
} catch {
    if (Test-Path -LiteralPath $gzFile) { Remove-Item -LiteralPath $gzFile -Force -ErrorAction SilentlyContinue }
    Fail 5 ('Gzip 压缩失败: ' + $_.Exception.Message)
}
Remove-Item -LiteralPath $rawFile -Force
$swZip.Stop()
$swTotal.Stop()

$bytes = (Get-Item -LiteralPath $gzFile).Length
$sizeKB = [math]::Round($bytes / 1KB, 1)

# 保留策略清理：只动形如 yyyyMMdd-HHmmss 的目录，避免误删人工放置的其他文件
$snapDirs = @()
try {
    $snapDirs = @(Get-ChildItem -LiteralPath $OutputDir -Directory |
        Where-Object { $_.Name -match '^\d{8}-\d{6}$' } |
        Sort-Object Name -Descending)
    if ($snapDirs.Count -gt $Keep) {
        $snapDirs | Select-Object -Skip $Keep | ForEach-Object {
            Remove-Item -LiteralPath $_.FullName -Recurse -Force
            Write-Host ('已清理旧快照目录: ' + $_.Name)
        }
    }
} catch {
    [Console]::Error.WriteLine(('清理旧快照失败（不影响本次快照，请人工检查）: ' + $_.Exception.Message))
}

Write-Host ('快照完成: ' + $gzFile)
Write-Host ('大小: ' + $sizeKB + ' KB（' + $bytes + ' bytes）')
Write-Host ('耗时: dump ' + [math]::Round($swDump.Elapsed.TotalSeconds, 1) + ' s + 压缩 ' + [math]::Round($swZip.Elapsed.TotalSeconds, 1) + ' s = 总 ' + [math]::Round($swTotal.Elapsed.TotalSeconds, 1) + ' s')
Write-Host ('binlog 位点: ' + $binlogPos)
Write-Host ('保留策略: 快照目录现存 ' + [math]::Min($snapDirs.Count, $Keep) + ' 份（Keep=' + $Keep + '）')
exit 0
