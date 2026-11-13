# Real Docker smoke test. No personal .env, mounts, integrations or downloads.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot\..\..").Path
$id = [guid]::NewGuid().ToString('N').Substring(0, 8)
$fixture = Join-Path $repo "data\launcher validation $id with spaces"
$project = "qlcstream-launcher-test-$id"
New-Item -ItemType Directory -Path (Join-Path $fixture 'scripts') -Force | Out-Null
Copy-Item -LiteralPath "$repo\scripts\Start-QLCStream.ps1", "$repo\scripts\WindowsLauncher.ps1" -Destination (Join-Path $fixture 'scripts')
Copy-Item -LiteralPath "$repo\iniciar-qlcstream.bat" -Destination $fixture
[IO.File]::WriteAllText((Join-Path $fixture '.env.example'), "# Synthetic fixture; no credentials`n")
# Reserve arbitrary free local ports before Compose starts.
$listeners = @(1, 2 | ForEach-Object { $l = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0); $l.Start(); $l })
$apiPort = $listeners[0].LocalEndpoint.Port
$uiPort = $listeners[1].LocalEndpoint.Port
$listeners | ForEach-Object { $_.Stop() }
$backendContext = (Join-Path $repo 'backend').Replace('\', '/')
$frontendContext = (Join-Path $repo 'frontend').Replace('\', '/')
$prepare = (Join-Path $repo 'backend/docker/prepare-directories.sh').Replace('\', '/')
$yaml = @"
name: $project
services:
  setup-directories:
    image: alpine:3.23@sha256:469c9ea410b9215eaee7976ba6c0639039b444be8e5d638f7abbada189971eb2
    user: root
    command: [sh, /prepare-directories.sh]
    volumes: ["keys:/secrets", "media:/media", "$($prepare):/prepare-directories.sh:ro"]
  database:
    image: postgres:18.6-alpine@sha256:9ba53c7d41ea093baf5c1ba635ba5434c4f979880527d49bb7d9237d3193085b
    environment:
      POSTGRES_DB: launcher_test
      POSTGRES_USER: launcher_test
      POSTGRES_PASSWORD: fictional_password
    volumes: [database:/var/lib/postgresql]
    healthcheck:
      test: [CMD-SHELL, pg_isready -U launcher_test -d launcher_test]
      interval: 2s
      timeout: 2s
      retries: 30
  backend:
    build: "$backendContext"
    networks: [default, preview]
    group_add: ["1000"]
    environment:
      DB_URL: jdbc:postgresql://database:5432/launcher_test
      DB_USER: launcher_test
      DB_PASSWORD: fictional_password
      MEDIA_ROOT: /data
      INTEGRATION_SETTINGS_KEY_FILE: /run/keys/integration-settings.key
    volumes: [keys:/run/keys, media:/data]
    ports: ["127.0.0.1:$($apiPort):8080"]
    depends_on:
      database:
        condition: service_healthy
      setup-directories:
        condition: service_completed_successfully
  frontend:
    build: "$frontendContext"
    networks: [default, preview]
    ports: ["127.0.0.1:$($uiPort):80"]
    depends_on: [backend]
volumes:
  keys:
  media:
  database:
networks:
  preview: {}
  default:
    internal: true
"@
[IO.File]::WriteAllText((Join-Path $fixture 'compose.yaml'), $yaml)
$compose = @('compose', '--project-directory', $fixture, '--env-file', (Join-Path $fixture '.env.example'), '-f', (Join-Path $fixture 'compose.yaml'))
function Check($Condition, $Message) { if (-not $Condition) { throw $Message } }
function Launch {
    # Exercise the public BAT from an unrelated working directory, in a space path.
    Push-Location $env:TEMP
    try { & (Join-Path $fixture 'iniciar-qlcstream.bat') -NoBrowser -TimeoutSeconds 180; Check ($LASTEXITCODE -eq 0) 'Launcher failed' }
    finally { Pop-Location }
}
try {
    Launch
    $envBytes = [IO.File]::ReadAllBytes((Join-Path $fixture '.env'))
    $before = @(& docker @compose ps --all --quiet)
    Check ($LASTEXITCODE -eq 0) 'Cannot snapshot fixture containers'
    $imagesBefore = @(& docker image inspect --format '{{.Id}}' "$project-backend" "$project-frontend")
    $keyState = Invoke-RestMethod "http://127.0.0.1:$apiPort/api/system/integration-settings/key"
    Check (-not $keyState.ready) 'Launcher provisioned a key implicitly'
    # Explicit fixture administration: provision a key and save a fictitious secret.
    Invoke-RestMethod -Method Post "http://127.0.0.1:$apiPort/api/system/integration-settings/key" | Out-Null
    $body = '{"source":"application","secret":{"action":"replace","value":"fictional-launcher-token"}}'
    Invoke-RestMethod -Method Put -ContentType application/json -Body $body "http://127.0.0.1:$apiPort/api/system/integration-settings/tmdb" | Out-Null
    $keyBefore = & docker @compose exec -T backend sha256sum /run/keys/integration-settings.key
    Check ($LASTEXITCODE -eq 0) 'Cannot hash synthetic key'
    Launch
    $after = @(& docker @compose ps --all --quiet)
    Check (($before -join ',') -eq ($after -join ',')) 'Repeated start recreated containers'
    # Put synthetic bytes in the database and media; they must survive stop/start.
    & docker @compose exec -T database psql -U launcher_test -d launcher_test -c 'CREATE TABLE launcher_sentinel (id integer); INSERT INTO launcher_sentinel VALUES (19);' | Out-Null
    Check ($LASTEXITCODE -eq 0) 'Cannot create synthetic database row'
    & docker @compose exec -T backend sh -c 'echo sentinel > /data/incoming/launcher-sentinel.txt'
    Check ($LASTEXITCODE -eq 0) 'Cannot create synthetic media'
    & docker @compose stop | Out-Null
    Check ($LASTEXITCODE -eq 0) 'Cannot stop fixture'
    Launch
    $resumed = @(& docker @compose ps --all --quiet)
    Check (($before -join ',') -eq ($resumed -join ',')) 'Resume recreated containers'
    $row = & docker @compose exec -T database psql -U launcher_test -d launcher_test -tAc 'SELECT id FROM launcher_sentinel'
    Check ($LASTEXITCODE -eq 0 -and "$row".Trim() -eq '19') 'Database row not preserved'
    $media = & docker @compose exec -T backend cat /data/incoming/launcher-sentinel.txt
    Check ($LASTEXITCODE -eq 0 -and "$media".Trim() -eq 'sentinel') 'Synthetic media not preserved'
    $imagesAfter = @(& docker image inspect --format '{{.Id}}' "$project-backend" "$project-frontend")
    Check (($imagesBefore -join ',') -eq ($imagesAfter -join ',')) 'Images changed on subsequent start'
    Check ([Convert]::ToBase64String($envBytes) -eq [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $fixture '.env')))) '.env changed'
    $settings = @(Invoke-RestMethod "http://127.0.0.1:$apiPort/api/system/integration-settings")
    Check (@($settings | Where-Object { $_.id -eq 'tmdb' -and $_.source -eq 'application' }).Count -eq 1) 'Managed integration not preserved'
    $keyAfter = & docker @compose exec -T backend sha256sum /run/keys/integration-settings.key
    Check ($LASTEXITCODE -eq 0 -and "$keyBefore" -eq "$keyAfter") 'Synthetic key changed'
    Write-Host 'PASS: real Windows BAT in space path; initial cached build; repeated launch; stop/start; same container/image IDs; env, database, key, managed integration and media preserved; setup initially available without key/credentials.'
} finally {
    # Stop/remove only this random fixture; preserve synthetic volumes and files for review.
    & docker @compose down | Out-Null
    Write-Host "Fixture preserved for review: data/launcher validation $id with spaces (volumes retained)."
}
