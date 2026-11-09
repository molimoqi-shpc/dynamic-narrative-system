# LEGACY V1 destructive seed verifier. Not a V2 formula acceptance test; do not run against live saves.
# V2: run StatsCalculatorTest and StatFormulaParityIT; read-only SQL: verify-six-stats-v2.sql.
<#
.SYNOPSIS
    One-click DB init + 6 walkthrough seed verifications.
.DESCRIPTION
    For CJK charset safety this .ps1 source contains ZERO Chinese characters.
    All SQL statements containing Chinese are written to a separate UTF-8+BOM
    temp .sql file (WriteAllText with explicit encoding + BOM), which cmd.exe
    pipes to the mysql client; mysql with --default-character-set=utf8mb4 reads
    it correctly, and only numeric output (0/1 flags) is compared back in PS.
.USAGE
    $env:MYSQL_BS_PASSWORD = "111111"
    powershell -NoProfile -ExecutionPolicy Bypass -File <THIS_FILE>
#>
$ErrorActionPreference = "Stop"

$mysql    = "D:\mysql-8.3.0-winx64\bin\mysql.exe"
$password = $env:MYSQL_BS_PASSWORD
if ([string]::IsNullOrWhiteSpace($password)) { $password = "111111" }
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$sqlInit   = Join-Path $scriptDir "init-all.sql"
$tmpDir    = Join-Path $env:TEMP ("bs_seed_" + [DateTime]::Now.ToString("yyyyMMddHHmmss"))
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$utf8Bom = [System.Text.UTF8Encoding]::new($true)

Write-Host "MYSQL CLIENT : $mysql"
Write-Host "SEED SQL     : $sqlInit"
Write-Host "TMP DIR      : $tmpDir"
if (-not (Test-Path $mysql))   { Write-Host "FATAL mysql.exe missing"; exit 2 }
if (-not (Test-Path $sqlInit)) { Write-Host "FATAL init-all.sql missing"; exit 3 }

function Run-Sql([string]$sqlText, [switch]$Show, [string]$Tag="sql") {
    $path = Join-Path $tmpDir ($Tag + "_" + [guid]::NewGuid().ToString("N").Substring(0,8) + ".sql")
    $preamble = "SET NAMES utf8mb4;" + [Environment]::NewLine + [Environment]::NewLine
    [System.IO.File]::WriteAllText($path, $preamble + $sqlText + [Environment]::NewLine, $utf8Bom)
    $expr = """$mysql"" --default-character-set=utf8mb4 -u root --password=""$password"" bs < ""$path"" 2>&1"
    $out = & cmd /c $expr 2>&1
    $ex  = $LASTEXITCODE
    if ($Show) {
        Write-Host "  ---- [$Tag] exit=$ex ----"
        $out | ForEach-Object { Write-Host ("    " + $_) }
    }
    return [pscustomobject]@{ Exit=$ex; Output=$out }
}

# ---- init schema + seeds ----
Write-Host "`n=== [1/3] INIT schema+seeds (DROP+CREATE+INSERT) ===" -ForegroundColor Cyan
$initText = "DROP TABLE IF EXISTS interaction_log, god_relationship, player_profile, game_scene, god_profile;" +
            [Environment]::NewLine + "SOURCE " + $sqlInit.Replace('\','/') + ";"
$r0 = Run-Sql -Show -Tag init -sqlText $initText
if ($r0.Exit -ne 0) { Write-Host "INIT FAIL exit=$($r0.Exit)" -ForegroundColor Red; exit 4 }
Write-Host "[1/3] INIT OK" -ForegroundColor Green

$pass = 0; $fail = 0
function Banner([string]$id,[string]$t){ Write-Host "`n=== CHECK $id`: $t ===" -ForegroundColor Cyan }
function Ok($id){ Write-Host "  [CHECK $id PASS]" -ForegroundColor Green; $script:pass++ }
function No($id,$m=""){ Write-Host "  [CHECK $id FAIL] $m" -ForegroundColor Red; $script:fail++ }

# C1 formula (loose matching: tab-separated fields, but the substrings "god_id 22 50 33" etc. will appear)
Banner C1 "actual_dou formula rain=33, player=12, museum=29, farm=5"
$r = Run-Sql -Show -Tag c1 -sqlText `
  "SELECT god_id,base_dou,base_ben,ROUND(base_dou*(1+base_ben/100)) AS actual FROM god_profile ORDER BY god_id;"
# Flatten entire output collection to one string (includes any warnings too; substrings still match)
$all = ($r.Output | Out-String)
$c1ok = ($all -match 'farm_god'   ) -and ($all -match '\b3\s+50\s+5\b')  -and
        ($all -match 'museum_god' ) -and ($all -match '\b18\s+60\s+29\b') -and
        ($all -match 'player_01'  ) -and ($all -match '\b10\s+20\s+12\b') -and
        ($all -match 'rain_god'    ) -and ($all -match '\b22\s+50\s+33\b')
if ($c1ok) { Ok C1 } else { No C1 "substring mismatch" }

# C2 scene JSON length
Banner C2 "scene_fengdu present_gods JSON length == 2"
$r = Run-Sql -Show -Tag c2 -sqlText `
  "SELECT scene_id, JSON_LENGTH(present_gods) AS c FROM game_scene;"
$all = ($r.Output | Out-String)
if ($all -match 'scene_fengdu' -and $all -match '\b2\b') { Ok C2 } else { No C2 "length not 2" }

# C3 directed pairs
Banner C3 "directed pairs museum->rain=30/1 rain->museum=10/0"
$r = Run-Sql -Show -Tag c3 -sqlText `
  "SELECT CONCAT(source_id,'->',target_id) AS pair,trust_value AS t,is_backstabbed AS bs FROM god_relationship WHERE (source_id='museum_god' AND target_id='rain_god') OR (source_id='rain_god' AND target_id='museum_god') ORDER BY pair;"
$all = ($r.Output | Out-String)
$c3ok = ($all -match 'museum_god->rain_god' ) -and ($all -match '\b30\s+1\b') -and
        ($all -match 'rain_god->museum_god' ) -and ($all -match '\b10\s+0\b')
if ($c3ok) { Ok C3 } else { No C3 "pair substring mismatch" }

# C4 inventory items.
#    IMPORTANT: NEVER write CJK literals directly in this .ps1 source file.
#    "powershell -File" on Chinese Windows reads .ps1 as ANSI (GBK), which
#    corrupts any embedded CJK character before the script even starts.
#    Instead, build 3 Chinese strings from Unicode codepoints at runtime:
#      ZHISHUI_FU = "止水符"  U+6B62 U+6C34 U+7B26
#      GUREN       = "骨刃"   U+9AA8 U+5203
#      SHUINANG    = "水囊"   U+6C34 U+56CA
#    Then write a UTF-8+BOM temp SQL containing LIKE '%...%' patterns.
#    Runtime-constructed .NET System.Char strings are always correct UTF-16
#    and File.WriteAllLines($utf8Bom) will encode them to UTF-8 properly.
Banner C4 "inventory rain=1/0/0  player=0/1/1"
$zhishuifu = [string]::Concat([char]0x6B62, [char]0x6C34, [char]0x7B26)
$guren     = [string]::Concat([char]0x9AA8, [char]0x5203)
$shuinang  = [string]::Concat([char]0x6C34, [char]0x56CA)
$c4lines = @(
    "SET NAMES utf8mb4;",
    "SELECT god_id,",
    ("  CASE WHEN CAST(inventory AS CHAR) LIKE '%" + $zhishuifu + "%' THEN 1 ELSE 0 END AS z,"),
    ("  CASE WHEN CAST(inventory AS CHAR) LIKE '%" + $guren     + "%' THEN 1 ELSE 0 END AS g,"),
    ("  CASE WHEN CAST(inventory AS CHAR) LIKE '%" + $shuinang  + "%' THEN 1 ELSE 0 END AS s"),
    "FROM god_profile WHERE god_id IN ('rain_god','player_01') ORDER BY god_id;"
)
$pathC4 = Join-Path $tmpDir ("c4_" + [guid]::NewGuid().ToString("N").Substring(0,6) + ".sql")
[System.IO.File]::WriteAllLines($pathC4, $c4lines, $utf8Bom)
# Run exactly like Run-Sql does: pass through cmd /c (so "<" redirection works
# as cmd syntax, not PS) and capture $LASTEXITCODE from cmd.
$exprC4 = """$mysql"" --default-character-set=utf8mb4 -u root --password=""$password"" bs < ""$pathC4"" 2>&1"
$outC4 = & cmd /c $exprC4 2>&1
$exC4 = $LASTEXITCODE
$all  = ($outC4 | Out-String)
Write-Host "  ---- [c4] exit=$exC4 ----"
$outC4 | ForEach-Object { Write-Host ("    " + $_) }
$rainChk   = [regex]::IsMatch($all, 'rain_god\s+1\s+0\s+0')
$playerChk = [regex]::IsMatch($all, 'player_01\s+0\s+1\s+1')
if ($rainChk -and $playerChk) { Ok C4 } else { No C4 ("rain=" + $rainChk + " player=" + $playerChk) }

# C5 unique key
Banner C5 "UNIQUE uk_source_target blocks reinsert museum->rain"
$r = Run-Sql -Show -Tag c5 -sqlText `
  "INSERT INTO god_relationship(source_id,target_id,trust_value,is_allied,is_backstabbed) VALUES ('museum_god','rain_god',50,0,0);"
$tx = ($r.Output -join " ")
if ($r.Exit -ne 0 -and $tx -match 'Duplicate') { Ok C5 } else { No C5 "not blocked" }

# C6 directed second edge rain->museum
Banner C6 "directed edge rain->museum exists independently (reinsert also Duplicate)"
$r = Run-Sql -Show -Tag c6 -sqlText `
  "INSERT INTO god_relationship(source_id,target_id,trust_value,is_allied,is_backstabbed) VALUES ('rain_god','museum_god',99,0,0);"
$tx = ($r.Output -join " ")
if ($r.Exit -ne 0 -and $tx -match 'Duplicate') {
    Ok C6
} else {
    No C6 "insert succeeded unexpectedly; will delete trust=99 row"
    Run-Sql -Tag c6clean -sqlText `
      "DELETE FROM god_relationship WHERE source_id='rain_god' AND target_id='museum_god' AND trust_value=99;" | Out-Null
}

Write-Host "`n==========================================================" -ForegroundColor Yellow
Write-Host "SUMMARY  PASS=$pass / FAIL=$fail   (target 6/0)" -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Yellow
if ($fail -eq 0 -and $pass -eq 6) {
    Write-Host "ALL 6 CHECKS PASSED  Step 1 + Step 2 verified." -ForegroundColor Green
    exit 0
} else {
    Write-Host "Some checks FAILED. Review raw output above." -ForegroundColor Red
    exit 1
}
