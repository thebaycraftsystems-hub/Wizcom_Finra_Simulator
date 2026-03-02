# Run FIX Simulator: uses Maven from PATH, or downloads Maven once to $env:USERPROFILE\.m2\maven
$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
$MavenVersion = "3.9.9"
$MavenDir = Join-Path (Join-Path $env:USERPROFILE ".m2") "maven"
$MavenZip = "apache-maven-$MavenVersion-bin.zip"
$MavenUrl = "https://archive.apache.org/dist/maven/maven-3/$MavenVersion/binaries/$MavenZip"

function Find-Maven {
    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mvn) { return $mvn.Source }
    $mvnCmd = Join-Path (Join-Path $MavenDir "apache-maven-$MavenVersion") (Join-Path "bin" "mvn.cmd")
    if (Test-Path $mvnCmd) { return $mvnCmd }
    return $null
}

function Install-Maven {
    $binDir = Join-Path $MavenDir "apache-maven-$MavenVersion"
    $mvnCmdPath = Join-Path $binDir (Join-Path "bin" "mvn.cmd")
    if (Test-Path $mvnCmdPath) {
        return $mvnCmdPath
    }
    New-Item -ItemType Directory -Force -Path $MavenDir | Out-Null
    $zipPath = Join-Path $MavenDir $MavenZip
    if (-not (Test-Path $zipPath)) {
        Write-Host "Downloading Maven $MavenVersion to $MavenDir ..."
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri $MavenUrl -OutFile $zipPath -UseBasicParsing
        } catch {
            Write-Host "Download failed. Please install Maven and add it to PATH, or run from Cursor using Run above main()." -ForegroundColor Yellow
            Write-Host "Error: $_"
            exit 1
        }
    }
    Write-Host "Extracting Maven..."
    Expand-Archive -Path $zipPath -DestinationPath $MavenDir -Force
    return $mvnCmdPath
}

$mvn = Find-Maven
if (-not $mvn) {
    $mvn = Install-Maven
}

Set-Location $ProjectRoot
Write-Host "Running: $mvn compile exec:java -Dexec.mainClass=com.wizcom.fix.simulator.Simulator" -ForegroundColor Cyan
& $mvn compile exec:java "-Dexec.mainClass=com.wizcom.fix.simulator.Simulator"
