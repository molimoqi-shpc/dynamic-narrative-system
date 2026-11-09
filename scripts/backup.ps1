param([Parameter(Mandatory=$true)][string]$Destination,[string]$MySqlDumpExe,[string]$Database='bs',[string]$DatabaseUser='root',[string]$DatabaseHost='localhost')
$ErrorActionPreference='Stop'
if(!$MySqlDumpExe){$MySqlDumpExe=(Get-Command mysqldump -ErrorAction Stop).Source}
if(!(Test-Path -LiteralPath $MySqlDumpExe)){throw 'mysqldump executable not found'}
if(!$env:MYSQL_BS_PASSWORD){throw 'Set MYSQL_BS_PASSWORD in this process before backing up'}
$projectDir=Split-Path $PSScriptRoot -Parent
$backupDir=Join-Path ([System.IO.Path]::GetFullPath($Destination)) ('story-backup-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
if(Test-Path -LiteralPath $backupDir){throw 'Backup directory already exists'}
New-Item -ItemType Directory -Path $backupDir|Out-Null
$sql=Join-Path $backupDir 'database.sql'
$start=New-Object System.Diagnostics.ProcessStartInfo
$start.FileName=$MySqlDumpExe;$start.UseShellExecute=$false;$start.CreateNoWindow=$true
$start.Arguments='--single-transaction --routines --triggers --hex-blob --default-character-set=utf8mb4 --host="'+$DatabaseHost+'" --user="'+$DatabaseUser+'" --result-file="'+$sql+'" "'+$Database+'"'
$start.EnvironmentVariables['MYSQL_PWD']=$env:MYSQL_BS_PASSWORD
$process=[System.Diagnostics.Process]::Start($start);$process.WaitForExit()
if($process.ExitCode -ne 0 -or !(Test-Path -LiteralPath $sql) -or (Get-Item -LiteralPath $sql).Length -eq 0){throw "Backup failed; partial output at $backupDir is not a usable backup"}
$keyDir=Join-Path $projectDir 'backend/.runtime'
if(Test-Path -LiteralPath $keyDir){Copy-Item -LiteralPath $keyDir -Destination (Join-Path $backupDir 'backend-runtime') -Recurse}
$stream=[System.IO.File]::OpenRead($sql);$hasher=[System.Security.Cryptography.SHA256]::Create()
try{$hash=[System.BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-','')}finally{$stream.Dispose();$hasher.Dispose()}
[ordered]@{createdAt=(Get-Date).ToString('o');database=$Database;sha256=$hash;authRuntimeIncluded=(Test-Path -LiteralPath $keyDir)}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $backupDir 'manifest.json') -Encoding UTF8
Write-Output "Backup complete: $backupDir"
