param([string]$PortName)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
  $portFilter = ''
  if ($PortName) {
    $portName = $PortName.Trim().ToUpperInvariant()
    if ($portName -notmatch '^(AC|DC)-\d{2,}-[A-Z0-9-]+$') {
      throw 'PortName must look like AC-03-TYPE2 or DC-01-CCS2.'
    }
    $portFilter = "AND upper(p.display_name) = '$portName'"
  }
  $limit = if ($PortName) { 2 } else { 1 }

  $sql = @"
SELECT p.station_id::text || ':' || p.id::text || ':' || p.display_name
FROM charging_sessions cs JOIN ports p ON p.id = cs.port_id
WHERE cs.status = 'CHARGING' $portFilter
ORDER BY cs.started_at DESC
LIMIT $limit;
"@
  $targets = @(docker compose exec -T postgres psql -qAt -v ON_ERROR_STOP=1 -U campus_charge -d campus_charge -c $sql | Where-Object { "$($_)".Trim() })
  if ($LASTEXITCODE -ne 0) { throw 'Could not inspect active charging sessions in PostgreSQL.' }
  if ($targets.Count -gt 1) { throw 'More than one active session matched. Check that the port name is unique.' }
  if ($targets.Count -eq 0) {
    if ($PortName) { throw "Port $PortName is not currently charging. Start charging on that port, then run this script again." }
    throw 'No port is currently charging. Start a charging session, then run this script again.'
  }

  $target = "$($targets[0])".Trim()
  $parts = $target.Split(':')
  $station = $parts[0]
  $port = $parts[1]
  $displayName = $parts[2].ToUpperInvariant()
  $demoMarker = "OVERHEAT-DEMO-$([guid]::NewGuid().ToString())"
  $body = @{
    eventId = [guid]::NewGuid().ToString()
    stationId = $station
    portId = $port
    action = 'OVERHEAT_WHILE_CHARGING'
    description = $demoMarker
    timestamp = [DateTimeOffset]::UtcNow.ToString('o')
    schemaVersion = 1
    temperatureC = 85.0
  } | ConvertTo-Json -Compress

  # Pass JSON through the environment, not native command arguments:
  # Windows PowerShell 5.1 strips JSON quotes from native arguments.
  $previousScenario = $env:SCENARIO
  $previousTopic = $env:TOPIC
  try {
    $env:SCENARIO = $body
    $env:TOPIC = "campus/$station/$port/scenarios"
    docker compose --profile tools run --rm -e SCENARIO -e TOPIC mqtt-tools
    if ($LASTEXITCODE -ne 0) { throw 'Could not publish the overheat scenario to the Compose MQTT broker.' }
  } finally {
    $env:SCENARIO = $previousScenario
    $env:TOPIC = $previousTopic
  }

  $verifySql = @"
SELECT CASE
  WHEN p.status = 'FAULTED'
    AND EXISTS (SELECT 1 FROM incidents i WHERE i.port_id=p.id AND i.description LIKE '%$demoMarker%')
    AND NOT EXISTS (SELECT 1 FROM charging_sessions cs WHERE cs.port_id=p.id AND cs.status<>'COMPLETED')
  THEN 'STOPPED'
  ELSE p.status || '|' || COALESCE((SELECT cs.status FROM charging_sessions cs WHERE cs.port_id=p.id ORDER BY cs.started_at DESC LIMIT 1),'NONE')
END
FROM ports p WHERE p.id='$port';
"@
  $stopped = $false
  for ($attempt = 0; $attempt -lt 30; $attempt++) {
    $state = docker compose exec -T postgres psql -qAt -v ON_ERROR_STOP=1 -U campus_charge -d campus_charge -c $verifySql
    if ($LASTEXITCODE -ne 0) { throw 'Could not verify the overheat incident in PostgreSQL.' }
    if ("$state".Trim() -eq 'STOPPED') { $stopped = $true; break }
    Start-Sleep -Seconds 1
  }
  if (-not $stopped) {
    Write-Host "Overheat message was published, but port $port did not reach FAULTED with its session stopped within 30 seconds. Current state: $($state.Trim())"
    docker compose logs --tail 40 simulator backend
    throw 'The simulator or backend did not complete the overheat safety flow. Review the log reason above.'
  }
  Write-Host "Overheat confirmed at 85 C: $displayName is FAULTED, an incident was recorded, and the charging session stopped. Open Admin > Sự cố to review it."
} finally {
  Pop-Location
}
