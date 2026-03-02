# Stop FIX Simulator by killing the process listening on port 64034.
# Usage: .\stop-simulator.ps1   or   powershell -File stop-simulator.ps1

$Port = 64034
$Conn = Get-NetTCPConnection -LocalPort $Port -ErrorAction SilentlyContinue | Where-Object { $_.State -eq 'Listen' }

if (-not $Conn) {
  Write-Host "No process found listening on port $Port. Simulator may already be stopped."
  exit 0
}

$Pid = $Conn.OwningProcess
Write-Host "Stopping FIX Simulator (PID $Pid on port $Port)..."
Stop-Process -Id $Pid -Force
Write-Host "Simulator stopped."
