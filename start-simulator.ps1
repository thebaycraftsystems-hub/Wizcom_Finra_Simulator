# Start FIX Simulator (Wizcom TRACE FIX Simulator)
# Usage: .\start-simulator.ps1   or   powershell -File start-simulator.ps1
# To run in background: Start-Process powershell -ArgumentList '-File', 'start-simulator.ps1', '-NoExit'

$Port = 64034
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

# Optional: stop any process already using the acceptor port
$Conn = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue | Where-Object { $_.State -eq 'Listen' }
if ($Conn) {
  $Pid = $Conn.OwningProcess
  Write-Host "Port $Port in use by PID $Pid. Stopping it first..."
  Stop-Process -Id $Pid -Force
  Start-Sleep -Seconds 2
}

Write-Host "Starting WIZCOM FIX Simulator..."
Write-Host ""
& mvn exec:java -q
