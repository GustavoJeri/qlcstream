$ErrorActionPreference = 'Stop'
$compose = Join-Path $PSScriptRoot 'storage-compose.yaml'
$api = 'http://127.0.0.1:18086/api'
function Docker-Test([string[]] $Arguments) {
    $result = & docker compose -f $compose @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Isolated Docker command failed: $Arguments" }
    return $result
}
function Save-Folder([string] $folder) {
    Invoke-RestMethod -Method Put -Uri "$api/library/storage" -ContentType 'application/json' -Body (@{ downloadDirectory = $folder } | ConvertTo-Json) | Out-Null
}
function Check([string] $backend, [string] $mapping, [string] $status) {
    $result = Invoke-RestMethod -Method Post -Uri "$api/system/storage/check"
    if ($result.backend -ne $backend -or $result.mapping -ne $mapping -or $result.status -ne $status -or $result.sharedDirectoryVerified) {
        throw "Unexpected diagnosis: $($result | ConvertTo-Json -Compress)"
    }
    $result | ConvertTo-Json -Compress | Write-Output
}
function Engine-Path([string] $path) {
    $form = 'json=' + [uri]::EscapeDataString((@{ save_path = $path } | ConvertTo-Json -Compress))
    Docker-Test @('exec', '-T', 'qbittorrent', 'wget', '-qO-', "--post-data=$form", 'http://localhost:8080/api/v2/app/setPreferences') | Out-Null
}

# This script addresses only the explicitly named test project and its synthetic records.
Docker-Test @('exec', '-T', 'qbittorrent', 'sh', '-c', 'mkdir -p /downloads/incoming /downloads/next /downloads/denied; chmod 777 /downloads/incoming /downloads/next; chmod 000 /downloads/denied; test -L /downloads/escape-test || ln -s /tmp /downloads/escape-test; printf issue16-volume-proof > /downloads/incoming/issue16-volume-proof.txt') | Out-Null
try {
    Engine-Path '/downloads'
    Save-Folder 'incoming'
    Check 'read_write_verified' 'compatible_unproven' 'inconclusive'
    $engineHash = (Docker-Test @('exec', '-T', 'qbittorrent', 'sha256sum', '/downloads/incoming/issue16-volume-proof.txt')).Split(' ')[0]
    $backendHash = (Docker-Test @('exec', '-T', 'backend', 'sha256sum', '/data/incoming/issue16-volume-proof.txt')).Split(' ')[0]
    if ($engineHash -ne $backendHash) { throw 'Shared file hashes differ' }
    Write-Output "Shared test file SHA-256: $backendHash"

    $sql = "INSERT INTO movie (tmdb_id, title) VALUES (161616, 'Storage fixture') ON CONFLICT (tmdb_id) DO NOTHING; INSERT INTO local_file (movie_id, storage_root_id, relative_path, file_kind, size_bytes) SELECT id, 1, 'incoming/issue16-volume-proof.txt', 'VIDEO', 20 FROM movie WHERE tmdb_id = 161616 ON CONFLICT (storage_root_id, relative_path) DO NOTHING;"
    Docker-Test @('exec', '-T', 'database', 'psql', '-U', 'storage_test', '-d', 'storage_test', '-v', 'ON_ERROR_STOP=1', '-c', $sql) | Out-Null
    Save-Folder 'missing-test'
    Check 'missing_directory' 'compatible_unproven' 'blocked'
    Save-Folder 'denied'
    Check 'permission_denied' 'compatible_unproven' 'blocked'
    Save-Folder 'escape-test'
    Check 'outside_root' 'compatible_unproven' 'blocked'
    Save-Folder 'incoming'
    Engine-Path '/outside-test'
    Check 'read_write_verified' 'mapping_incompatible' 'blocked'
    Engine-Path '/downloads'
    Save-Folder 'next'
    Check 'read_write_verified' 'compatible_unproven' 'inconclusive'
    $library = Invoke-RestMethod "$api/library"
    if (-not ($library | Where-Object relativePath -eq 'incoming/issue16-volume-proof.txt')) { throw 'Earlier library record is missing' }
    $after = (Docker-Test @('exec', '-T', 'backend', 'sha256sum', '/data/incoming/issue16-volume-proof.txt')).Split(' ')[0]
    if ($after -ne $backendHash) { throw 'Earlier file changed' }
    $probes = Docker-Test @('exec', '-T', 'backend', 'find', '/data/incoming', '/data/next', '-name', '.qlc-storage-check-*')
    if ($probes) { throw "Probe files remain: $probes" }
    $torrents = Docker-Test @('exec', '-T', 'qbittorrent', 'wget', '-qO-', 'http://localhost:8080/api/v2/torrents/info')
    if (($torrents | Out-String).Trim() -ne '[]') { throw 'Unexpected torrent in isolated engine' }
    Write-Output 'PASS: prior library record and file preserved; no probe residue; zero torrents.'
} finally {
    Engine-Path '/downloads'
    Save-Folder 'incoming'
    Docker-Test @('exec', '-T', 'qbittorrent', 'chmod', '777', '/downloads/denied') | Out-Null
}
