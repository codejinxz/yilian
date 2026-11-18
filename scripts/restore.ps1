<#
.SYNOPSIS
  从快照还原 MySQL 数据库（默认还原到隔离演练库）。

.DESCRIPTION
  还原流程：读取对账源库的字符集/排序规则 → drop/create 目标库（对齐源库）→
  mysql 导入快照（.sql.gz 先解压为临时文件，导入完即删）→ 核心表行数对账
  （目标库 vs 当前源库）。

  防误操作：
    - 目标库为系统库（information_schema/mysql/performance_schema/sys）一律拒绝；
    - 目标库为 yilian 主库时拒绝，只有显式 -Force 才放行（防误删现网数据）；
    - 目标库与对账源库同名时拒绝（同库自还原同样会 drop 现网数据）；
    - 快照路径含空白字符时拒绝（mysql 的 source 语句在 PowerShell 5.1 传参下
      无法安全携带引号路径）。

  对账口径：users/sentences/sentence_versions/attempts/attempt_gradings/
  sentence_banks/prompt_versions/appeals/reviews 的 COUNT(*) 与当前源库对比；
  源库是当前 live 数据，快照时点之后的新增写入属合法漂移，对账表如实显示
  差异并以非零码退出（5），交人工核对。

  退出码：0 成功且对账一致；2 用法/参数错误；3 快照文件缺失/格式不支持/解压失败；
  4 建库或导入失败；5 对账不一致或对账执行失败。

  凭据纪律：与 snapshot.ps1 相同——密码按 -Passwd > YILIAN_DB_PASSWORD >
  MYSQL_PWD 顺序取用，全部缺失时报错打印用法，不内置任何默认密码。

.EXAMPLE
  powershell -File scripts/restore.ps1 -Snapshot server/data/snapshots/20261119-120000/yilian-full.sql.gz -Passwd '***'
.EXAMPLE
  powershell -File scripts/restore.ps1 -Snapshot <路径> -TargetDB yilian -Force   # 高危：覆盖主库
#>
[CmdletBinding()]
param(
    # 快照文件路径（.sql.gz 或 .sql）；路径不得含空白字符
    [Parameter(Mandatory = $true)]
    [string]$Snapshot,
    # 目标库；默认隔离演练库
    [string]$TargetDB = 'yilian_drill',
    # 对账基准库（当前 live 数据）；传空串可跳过字符集对齐与对账
    [string]$SourceDB = 'yilian',
    # 显式放行还原到 yilian 主库（高危，需人工确认后使用）
    [switch]$Force,
    # MySQL 用户名
    [string]$U = 'root',
    # MySQL 端口
    [int]$P = 3306,
    # MySQL 主机
    [string]$DbHost = 'localhost',
    # 数据库密码；缺省回落环境变量，不设硬编码默认值
    [string]$Passwd = ''
)

$ErrorActionPreference = 'Stop'

function Show-Usage {
    Write-Host '用法: powershell -File scripts/restore.ps1 -Snapshot <快照路径> [-TargetDB yilian_drill] [-SourceDB yilian] [-Force] [-U root] [-P 3306] [-DbHost localhost] [-Passwd ***]'
    Write-Host '密码来源（按优先级）: -Passwd 参数 > 环境变量 YILIAN_DB_PASSWORD > 环境变量 MYSQL_PWD；三者全缺时拒绝执行。'
    Write-Host '安全边界: 还原到 yilian 主库必须显式 -Force；系统库（information_schema/mysql/performance_schema/sys）一律拒绝。'
}

function Fail {
    param([int]$ExitCode, [string]$Message)
    [Console]::Error.WriteLine($Message)
    exit $ExitCode
}

function Get-TableCounts {
    param([string]$Db, [string[]]$TableList)
    $selects = @()
    foreach ($t in $TableList) {
        $selects += ('SELECT ''' + $t + ''', COUNT(*) FROM ' + $Db + '.' + $t)
    }
    $q = [string]::Join(' UNION ALL ', $selects)
    & $script:mysql ('--host=' + $script:DbHost) ('--port=' + $script:P) ('--user=' + $script:U) -N -B -e $q
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
}

# --- 快照文件校验 ---
if ([string]::IsNullOrEmpty($Snapshot)) {
    Show-Usage
    Fail 2 '缺少 -Snapshot 参数。'
}
if ($Snapshot -match '\s') {
    Show-Usage
    Fail 2 ('快照路径含空白字符（mysql source 传参限制）: ' + $Snapshot)
}
if (-not [System.IO.Path]::IsPathRooted($Snapshot)) {
    $Snapshot = Join-Path (Get-Location).Path $Snapshot
}
$Snapshot = [System.IO.Path]::GetFullPath($Snapshot)
if (-not (Test-Path -LiteralPath $Snapshot -PathType Leaf)) {
    Show-Usage
    Fail 3 ('快照文件不存在: ' + $Snapshot)
}
$ext = [System.IO.Path]::GetExtension($Snapshot).ToLowerInvariant()
if ($ext -ne '.gz' -and $ext -ne '.sql') {
    Show-Usage
    Fail 3 ('不支持的快照格式（只接受 .sql.gz / .sql）: ' + $Snapshot)
}

# --- 目标库防御：系统库一律拒绝；主库与同名自还原需 -Force ---
if ($TargetDB -notmatch '^[A-Za-z0-9_]+$') {
    Show-Usage
    Fail 2 ('非法目标库名: ' + $TargetDB)
}
$systemDbs = @('information_schema', 'mysql', 'performance_schema', 'sys')
if ($systemDbs -contains $TargetDB.ToLowerInvariant()) {
    Fail 2 ('拒绝还原到系统库: ' + $TargetDB)
}
if (-not $Force -and $TargetDB -ieq 'yilian') {
    Show-Usage
    Fail 2 '拒绝还原到主库 yilian: 该操作会 drop 现网数据。确要执行需显式加 -Force。'
}
if (-not [string]::IsNullOrEmpty($SourceDB)) {
    if ($SourceDB -notmatch '^[A-Za-z0-9_]+$') {
        Fail 2 ('非法对账源库名: ' + $SourceDB)
    }
    if (-not $Force -and $TargetDB -ieq $SourceDB) {
        Fail 2 ('目标库与对账源库同名（' + $TargetDB + '）: 会 drop 当前数据，拒绝执行；确要执行需显式 -Force。')
    }
}

# --- 密码解析：任何默认值都不提供，缺失即报用法错误 ---
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

$mysql = (Get-Command mysql -ErrorAction SilentlyContinue).Source
if ([string]::IsNullOrEmpty($mysql)) {
    Show-Usage
    Fail 2 'mysql 客户端不在 PATH 中；请确认 MySQL 8.0 bin 目录已加入 PATH。'
}

$sw = [System.Diagnostics.Stopwatch]::StartNew()

# 临时解压文件放快照同目录（无空白路径已校验），导入完即删
$tempSql = ''
$importPath = $Snapshot
if ($ext -eq '.gz') {
    try {
        Add-Type -AssemblyName System.IO.Compression
        $tempSql = Join-Path (Split-Path -Parent $Snapshot) ('.restore-' + $PID + '.sql')
        $inStream = [System.IO.File]::OpenRead($Snapshot)
        $outStream = [System.IO.File]::Create($tempSql)
        $gz = New-Object System.IO.Compression.GzipStream($inStream, [System.IO.Compression.CompressionMode]::Decompress)
        try {
            $gz.CopyTo($outStream)
        } finally {
            $gz.Dispose()
            $outStream.Dispose()
            $inStream.Dispose()
        }
        $importPath = $tempSql
    } catch {
        if ($tempSql -ne '' -and (Test-Path -LiteralPath $tempSql)) {
            Remove-Item -LiteralPath $tempSql -Force -ErrorAction SilentlyContinue
        }
        Fail 3 ('解压快照失败: ' + $_.Exception.Message)
    }
}

# mysql source 的路径统一正斜杠，避免反斜杠被当作转义符
$importPathFwd = $importPath.Replace('\', '/')

$env:MYSQL_PWD = $Passwd
$exitCode = 0
try {
    # 对齐源库字符集/排序规则；源库缺失视为参数错误而非静默降级
    $createCharset = 'utf8mb4'
    $createCollate = 'utf8mb4_0900_ai_ci'
    if (-not [string]::IsNullOrEmpty($SourceDB)) {
        $csQuery = 'SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=''' + $SourceDB + ''''
        $csRow = @(& $mysql ('--host=' + $DbHost) ('--port=' + $P) ('--user=' + $U) -N -B -e $csQuery)
        if ($LASTEXITCODE -ne 0 -or $csRow.Count -eq 0) {
            Fail 2 ('对账源库不存在或不可读: ' + $SourceDB)
        }
        $parts = ($csRow | Select-Object -First 1) -split "`t"
        if ($parts.Count -ge 2 -and $parts[0] -ne '') {
            $createCharset = $parts[0]
            $createCollate = $parts[1]
        }
    }

    $setupQuery = 'DROP DATABASE IF EXISTS ' + $TargetDB + '; CREATE DATABASE ' + $TargetDB + ' CHARACTER SET ' + $createCharset + ' COLLATE ' + $createCollate + ';'
    & $mysql ('--host=' + $DbHost) ('--port=' + $P) ('--user=' + $U) -e $setupQuery
    if ($LASTEXITCODE -ne 0) {
        Fail 4 ('drop/create 目标库失败: ' + $TargetDB)
    }

    $sourceQuery = 'source ' + $importPathFwd
    & $mysql ('--host=' + $DbHost) ('--port=' + $P) ('--user=' + $U) '--default-character-set=utf8mb4' ('--database=' + $TargetDB) -e $sourceQuery
    if ($LASTEXITCODE -ne 0) {
        Fail 4 ('导入快照失败: ' + $Snapshot)
    }

    # 从快照头部读取 binlog 位点注释，供 PITR 记账
    $binlogPos = '(未找到位点注释)'
    $head = @(Get-Content -LiteralPath $importPath -TotalCount 200)
    foreach ($line in $head) {
        if ($line -match 'CHANGE REPLICATION SOURCE TO') { $binlogPos = $line.Trim(); break }
        if ($line -match 'CHANGE MASTER TO') { $binlogPos = $line.Trim(); break }
    }

    $sw.Stop()
    $importSeconds = [math]::Round($sw.Elapsed.TotalSeconds, 1)

    if (-not [string]::IsNullOrEmpty($SourceDB)) {
        # 核心表行数对账：覆盖账号/题目/判分/复习/申诉主链
        $tables = @('users', 'sentences', 'sentence_versions', 'attempts', 'attempt_gradings', 'sentence_banks', 'prompt_versions', 'appeals', 'reviews')
        $srcCounts = Get-TableCounts -Db $SourceDB -TableList $tables
        $tgtCounts = Get-TableCounts -Db $TargetDB -TableList $tables
        if ($null -eq $srcCounts -or $null -eq $tgtCounts) {
            Fail 5 ('行数对账查询执行失败（源库或目标库不可读）。')
        }

        $srcMap = @{}
        foreach ($row in @($srcCounts)) {
            $kv = $row -split "`t"
            if ($kv.Count -ge 2) { $srcMap[$kv[0]] = $kv[1] }
        }
        $tgtMap = @{}
        foreach ($row in @($tgtCounts)) {
            $kv = $row -split "`t"
            if ($kv.Count -ge 2) { $tgtMap[$kv[0]] = $kv[1] }
        }

        $rows = @()
        $allMatch = $true
        foreach ($t in $tables) {
            $s = if ($srcMap.ContainsKey($t)) { $srcMap[$t] } else { 'MISSING' }
            $g = if ($tgtMap.ContainsKey($t)) { $tgtMap[$t] } else { 'MISSING' }
            if ($s -ne $g) { $allMatch = $false }
            $rows += [pscustomobject]@{ table = $t; source = $s; target = $g; match = ($s -eq $g) }
        }

        Write-Host ('还原完成: ' + $TargetDB + '，导入耗时 ' + $importSeconds + ' s')
        Write-Host ('快照 binlog 位点: ' + $binlogPos)
        Write-Host '行数对账（源库=当前 live 数据；快照时点后的新增写入属合法漂移）:'
        Write-Host (($rows | Format-Table -AutoSize | Out-String).TrimEnd())

        if (-not $allMatch) {
            [Console]::Error.WriteLine('对账不一致: 上表 match=false 的表请人工核对（可能是快照后源库又有写入，也可能还原不完整）。')
            $exitCode = 5
        } else {
            Write-Host '对账结论: 全部一致。'
        }
    } else {
        Write-Host ('还原完成: ' + $TargetDB + '，导入耗时 ' + $importSeconds + ' s（未指定对账源库，跳过对账）')
        Write-Host ('快照 binlog 位点: ' + $binlogPos)
    }
} finally {
    if ($tempSql -ne '' -and (Test-Path -LiteralPath $tempSql)) {
        Remove-Item -LiteralPath $tempSql -Force -ErrorAction SilentlyContinue
    }
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
}

exit $exitCode
