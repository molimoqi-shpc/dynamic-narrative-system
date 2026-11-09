param([string]$JavaExe,[string]$NodeExe,[ValidateRange(1,100)][int]$MaxModelCalls=24)
$ErrorActionPreference='Stop'
$projectDir=Split-Path $PSScriptRoot -Parent
$runtimeDir=Join-Path $projectDir '.runtime'
New-Item -ItemType Directory -Force $runtimeDir|Out-Null
if(!$JavaExe){if($env:JAVA_HOME){$JavaExe=Join-Path $env:JAVA_HOME 'bin/java.exe'}else{$JavaExe=(Get-Command java -ErrorAction Stop).Source}}
if(!$NodeExe){$NodeExe=(Get-Command node -ErrorAction Stop).Source}
$jarPath=Join-Path $projectDir 'backend/target/narrative-backend-0.1.0-SNAPSHOT.jar'
$vitePath=Join-Path $projectDir 'frontend/node_modules/vite/bin/vite.js'
foreach($file in @($JavaExe,$NodeExe,$jarPath,$vitePath)){if(!(Test-Path -LiteralPath $file)){throw "Missing runtime/build file: $file"}}
$listeningPorts=@([System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()|ForEach-Object {$_.Port})
foreach($port in @(8090,5173)){if($listeningPorts -contains $port){throw "Port $port is in use. Existing services have not been changed."}}
$tag=Get-Date -Format 'yyyyMMdd-HHmmss'
$started=@()
try{
 foreach($kind in @('backend','frontend')){
  if($kind -eq 'backend'){
   $exe=$JavaExe;$port=8090;$working=Join-Path $projectDir 'backend'
   $arguments=@('-jar',('"'+$jarPath+'"'),'--server.port=8090','--server.address=127.0.0.1','--spring.jpa.show-sql=false',"--game.operation-max-model-calls=$MaxModelCalls")
  }else{
   $exe=$NodeExe;$port=5173;$working=Join-Path $projectDir 'frontend'
   $arguments=@(('"'+$vitePath+'"'),'--host','127.0.0.1','--port','5173','--strictPort')
  }
  $stdout=Join-Path $runtimeDir "$kind-$tag-out.log";$stderr=Join-Path $runtimeDir "$kind-$tag-err.log"
  $process=Start-Process -FilePath $exe -ArgumentList $arguments -WorkingDirectory $working -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
  $record=[ordered]@{kind=$kind;pid=$process.Id;executable=[System.IO.Path]::GetFullPath($exe);startTime=$process.StartTime.ToString('o');port=$port;stdout=$stdout;stderr=$stderr}
  if($kind -eq 'backend'){
   $stream=[System.IO.File]::OpenRead($jarPath);$hasher=[System.Security.Cryptography.SHA256]::Create()
   try{$record.package=$jarPath;$record.sha256=[System.BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-','')}finally{$stream.Dispose();$hasher.Dispose()}
  }
  $record|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $runtimeDir "$kind.json") -Encoding UTF8
  $started+=@{process=$process;manifest=(Join-Path $runtimeDir "$kind.json")}
 }
 $ready=$false
 for($attempt=0;$attempt -lt 45;$attempt++){
  foreach($entry in $started){if($entry.process.HasExited){throw "Service exited; inspect logs in $runtimeDir"}}
  try{
   $health=Invoke-RestMethod 'http://127.0.0.1:8090/actuator/health' -TimeoutSec 2
   $page=Invoke-WebRequest 'http://127.0.0.1:5173' -TimeoutSec 2 -UseBasicParsing
   if($health.status -eq 'UP' -and $page.StatusCode -eq 200){$ready=$true;break}
  }catch{}
  Start-Sleep -Seconds 1
 }
 if(!$ready){throw "Services did not become ready; inspect $runtimeDir"}
 Write-Output 'Ready: http://127.0.0.1:5173 (backend 8090). Health checks made no model calls.'
}catch{
 foreach($entry in $started){if(!$entry.process.HasExited){Stop-Process -Id $entry.process.Id};Remove-Item -LiteralPath $entry.manifest -ErrorAction SilentlyContinue}
 throw
}
