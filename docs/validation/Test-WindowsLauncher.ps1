# Dependency-free contract tests. Docker, HTTP and browser are controlled doubles.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\..\..\scripts\WindowsLauncher.ps1"
$realPortProbe = ${function:Test-LauncherPort}
$realDocker = ${function:Invoke-LauncherDocker}
$realHttp = ${function:Test-LauncherHttp}
$script:checks = 0
function Assert($Condition, $Message) {
    if (-not $Condition) { throw "FAILED: $Message" }
    $script:checks++
}
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('QLC launcher with spaces ' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
[IO.File]::WriteAllText((Join-Path $testRoot '.env.example'), 'TMDB_API_TOKEN=')
$script:config = @{
    name = 'launcher-test'
    services = @{
        backend = @{build = @{}; ports = @(@{published = '18090'; target = 8080; protocol = 'tcp'; host_ip = '127.0.0.1'})}
        frontend = @{build = @{}; ports = @(@{published = '14290'; target = 80; protocol = 'tcp'; host_ip = '127.0.0.1'})}
        qbittorrent = @{image = 'fictional'; ports = @(@{published = '16890'; target = 6881; protocol = 'udp'})}
    }
} | ConvertTo-Json -Depth 12
function Reset-Scenario {
    $script:scenario = ''; $script:calls = @(); $script:messages = @(); $script:opened = @(); $script:probes = 0
    $script:containers = ''
}
function Write-Host { param($Object) $script:messages += [string]$Object }
function Open-LauncherBrowser { param($Url) $script:opened += $Url }
function Test-LauncherPort { param($Address, $Port, $Protocol) return ($script:scenario -ne 'port' -and $script:scenario -ne "port-$Protocol") }
function Test-LauncherHttp {
    param($Url, [switch]$Health)
    $script:probes++
    return ($script:scenario -ne 'health' -and ($script:scenario -ne 'delayed' -or $script:probes -gt 2))
}
function Get-Command {
    param($Name, $ErrorAction)
    if ($script:scenario -eq 'missing-docker') { return $null }
    return @{Name = 'docker'}
}
function Invoke-LauncherDocker {
    param([string[]]$Arguments)
    $script:calls += ,$Arguments
    $code = 0; $text = ''
    if ($Arguments -contains 'version' -and $script:scenario -eq 'compose') { $code = 1 }
    if ($Arguments[0] -eq 'info' -and $script:scenario -eq 'engine') { $code = 1 }
    if ($Arguments -contains 'config') { $text = $script:config; if ($script:scenario -eq 'config') { $code = 1 } }
    if ($Arguments -contains 'ps') { $text = $script:containers; if ($script:scenario -eq 'ps') { $code = 1 } }
    if ($Arguments[0] -eq 'inspect') {
        $dir = $testRoot
        if ($script:scenario -eq 'foreign') { $dir = 'C:\other installation' }
        $text = @{'com.docker.compose.project.working_dir' = $dir} | ConvertTo-Json -Compress
    }
    if ($Arguments[0] -eq 'image' -and $script:scenario -in @('first', 'build')) { $code = 1 }
    if ($Arguments -contains 'build' -and $script:scenario -eq 'build') { $code = 1 }
    if ($Arguments -contains 'up' -and $script:scenario -eq 'up') { $code = 1 }
    if ($code) { $text = 'fictional-secret-must-not-escape' }
    return @{Code = $code; Text = $text}
}
function Has-Call($Word) { return @($script:calls | Where-Object { $_ -contains $Word }).Count -gt 0 }
try {
    Reset-Scenario
    $script:scenario = 'first'
    Assert ((Start-QLCStream $testRoot) -eq 0) 'first start succeeds without credentials'
    Assert (Test-Path -LiteralPath (Join-Path $testRoot '.env')) 'example created'
    Assert (Has-Call 'build') 'missing images built'
    Assert (Has-Call '--no-recreate') 'up preserves existing containers'
    Assert (Has-Call '--no-build') 'up cannot implicitly build'
    Assert ($script:opened.Count -eq 1 -and $script:probes -eq 2) 'browser opens after both probes'
    Assert (@($script:calls | Where-Object { $_ -contains $testRoot }).Count -gt 0) 'space path is one argument'
    [IO.File]::WriteAllText((Join-Path $testRoot '.env'), 'fictional-personal-configuration')
    Reset-Scenario
    Assert ((Start-QLCStream $testRoot -NoBrowser) -eq 0) 'repeat start succeeds'
    Assert (-not (Has-Call 'build')) 'existing images not rebuilt'
    Assert ($script:opened.Count -eq 0) 'NoBrowser honored'
    Assert ([IO.File]::ReadAllText((Join-Path $testRoot '.env')) -eq 'fictional-personal-configuration') 'existing env bytes preserved'
    foreach ($case in @(
        @('missing-docker', 'DOCKER_AUSENTE'), @('compose', 'COMPOSE_INDISPONIVEL'), @('engine', 'DOCKER_INDISPONIVEL'),
        @('config', 'CONFIGURACAO'), @('ps', 'INICIALIZACAO'), @('port-tcp', 'PORTA_OCUPADA'), @('port-udp', 'PORTA_OCUPADA'),
        @('build', 'BUILD'), @('up', 'INICIALIZACAO'), @('health', 'SAUDE')
    )) {
        Reset-Scenario; $script:scenario = $case[0]
        Assert ((Start-QLCStream $testRoot -TimeoutSeconds 1) -eq 1) "$($case[0]) fails"
        Assert (($script:messages -join ' ') -match "\[ERRO\] $($case[1]):") "$($case[0]) classified"
        Assert ($script:opened.Count -eq 0) "$($case[0]) never opens browser"
        Assert (($script:messages -join ' ') -notmatch 'fictional-secret') "$($case[0]) redacts native output"
        if ($case[0] -notin @('up','health')) { Assert (-not (Has-Call 'up')) "$($case[0]) fails before mutation" }
    }
    $running = @(
        @{ID='backend-id'; Service='backend'; State='running'; Publishers=@(@{PublishedPort=18090; TargetPort=8080; Protocol='tcp'})},
        @{ID='frontend-id'; Service='frontend'; State='running'; Publishers=@(@{PublishedPort=14290; TargetPort=80; Protocol='tcp'})},
        @{ID='qbit-id'; Service='qbittorrent'; State='running'; Publishers=@(@{PublishedPort=16890; TargetPort=6881; Protocol='udp'})}
    )
    foreach ($format in @('array', 'lines')) {
        Reset-Scenario; $script:scenario = 'port'
        if ($format -eq 'array') { $script:containers = ConvertTo-Json -InputObject $running -Depth 6 }
        else { $script:containers = ($running | ForEach-Object { $_ | ConvertTo-Json -Compress -Depth 6 }) -join "`n" }
        Assert ((Start-QLCStream $testRoot) -eq 0) "running app recognized ($format) despite occupied ports"
        Assert (-not (Has-Call 'up')) 'running application not mutated'
        Assert ($script:opened.Count -eq 1) 'running healthy app opens browser'
    }
    Reset-Scenario; $script:scenario = 'foreign'; $script:containers = ConvertTo-Json -InputObject $running -Depth 6
    Assert ((Start-QLCStream $testRoot) -eq 1) 'foreign project rejected'
    Assert (($script:messages -join ' ') -match 'OUTRA_INSTALACAO:') 'foreign project explained'
    Assert (-not (Has-Call 'up')) 'foreign project untouched'
    Reset-Scenario; $script:scenario = 'delayed'
    Assert ((Start-QLCStream $testRoot) -eq 0 -and $script:probes -gt 2) 'delayed backend waits before browser'
    Reset-Scenario
    $handle = [IO.File]::Open((Join-Path $testRoot '.qlcstream-start.lock'), 'Open', 'ReadWrite', 'None')
    try { Assert ((Start-QLCStream $testRoot) -eq 1) 'concurrent start refused' } finally { $handle.Dispose() }
    Assert (-not (Has-Call 'up')) 'concurrent start does not mutate'
    # Real Windows socket checks, using ephemeral TCP and UDP ports.
    $tcp = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $tcp.Start()
    $tcpPort = $tcp.LocalEndpoint.Port
    try { Assert (-not (& $realPortProbe '127.0.0.1' $tcpPort 'tcp')) 'real occupied TCP detected' } finally { $tcp.Stop() }
    Assert (& $realPortProbe '127.0.0.1' $tcpPort 'tcp') 'released TCP available'
    $udp = New-Object Net.Sockets.UdpClient(0)
    try { Assert (-not (& $realPortProbe '0.0.0.0' $udp.Client.LocalEndPoint.Port 'udp')) 'real occupied UDP detected' } finally { $udp.Dispose() }
    # Test redaction/capture of native stdout+stderr with a synthetic executable function.
    function docker { & powershell.exe -NoProfile -Command "[Console]::Error.WriteLine('synthetic-error'); Write-Output 'synthetic-output'; exit 7" }
    $native = & $realDocker @('fictional')
    Assert ($native.Code -eq 7 -and $native.Text -match 'synthetic-output' -and $native.Text -match 'synthetic-error') 'native exit status and both output streams captured'
    # Exercise real response parsing, including PowerShell 5.1 vendor JSON byte[].
    function Invoke-WebRequest {
        param($Uri, [switch]$UseBasicParsing, $TimeoutSec, $MaximumRedirection)
        return @{StatusCode = $script:httpStatus; Content = $script:httpContent}
    }
    $script:httpStatus = 200
    foreach ($body in @('{"status":"UP"}', [Text.Encoding]::UTF8.GetBytes('{"status":"UP"}'))) {
        $script:httpContent = $body
        Assert (& $realHttp 'http://127.0.0.1/health' -Health) 'health accepts text and vendor JSON bytes'
    }
    foreach ($body in @('{"status":"DOWN"}', '{}', 'invalid')) {
        $script:httpContent = $body
        Assert (-not (& $realHttp 'http://127.0.0.1/health' -Health)) 'unhealthy/invalid JSON refused'
    }
    $script:httpContent = '<app-root></app-root>'
    Assert (& $realHttp 'http://127.0.0.1') 'application HTML accepted'
    $script:httpContent = '<html>unrelated service</html>'
    Assert (-not (& $realHttp 'http://127.0.0.1')) 'unrelated HTTP page refused'
    $script:httpStatus = 503; $script:httpContent = '{"status":"UP"}'
    Assert (-not (& $realHttp 'http://127.0.0.1/health' -Health)) 'HTTP failure refused'
    Microsoft.PowerShell.Utility\Write-Host "PASS: $script:checks assertions (controlled Docker/HTTP/browser; real Windows sockets and file locking)."
} finally {
    # Only the random synthetic directory created above is removed.
    if ((Split-Path $testRoot -Leaf) -like 'QLC launcher with spaces *') { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
# GitHub's PowerShell wrapper propagates LASTEXITCODE. The deliberate native
# failure above is asserted successfully and must not mark the suite as failed.
exit 0
