$ErrorActionPreference='Stop'
$runtimeDir=Join-Path (Split-Path $PSScriptRoot -Parent) '.runtime'
foreach($kind in @('frontend','backend')){
 $manifest=Join-Path $runtimeDir "$kind.json"
 if(!(Test-Path -LiteralPath $manifest)){continue}
 $record=Get-Content -LiteralPath $manifest -Raw|ConvertFrom-Json
 $process=Get-Process -Id $record.pid -ErrorAction SilentlyContinue
 if($process){
  if($process.Path -ne $record.executable -or $process.StartTime -ne [datetime]$record.startTime){throw "Process identity changed for $kind; nothing stopped"}
  Stop-Process -Id $process.Id -ErrorAction Stop
  $process.WaitForExit()
 }
 Remove-Item -LiteralPath $manifest
 Write-Output "Stopped managed $kind"
}
