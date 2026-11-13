# Native output stays private: Compose errors and container logs may contain secrets.
function Invoke-LauncherDocker {
    param([string[]]$Arguments)
    try {
        $ErrorActionPreference = 'Continue'
        $output = & docker @Arguments 2>&1
        return @{ Code = $LASTEXITCODE; Text = ($output -join "`n") }
    } catch { return @{ Code = 1; Text = '' } }
}

function Test-LauncherPort {
    param([string]$Address, [int]$Port, [string]$Protocol)
    $socket = $null
    try {
        $ip = [System.Net.IPAddress]::Parse($Address)
        $type = [System.Net.Sockets.SocketType]::Stream
        $transport = [System.Net.Sockets.ProtocolType]::Tcp
        if ($Protocol -eq 'udp') {
            $type = [System.Net.Sockets.SocketType]::Dgram
            $transport = [System.Net.Sockets.ProtocolType]::Udp
        }
        $socket = New-Object System.Net.Sockets.Socket($ip.AddressFamily, $type, $transport)
        $socket.ExclusiveAddressUse = $true
        $socket.Bind((New-Object System.Net.IPEndPoint($ip, $Port)))
        return $true
    } catch { return $false } finally { if ($socket) { $socket.Dispose() } }
}

function Test-LauncherHttp {
    param([string]$Url, [switch]$Health)
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3 -MaximumRedirection 0
        if ($response.StatusCode -ne 200) { return $false }
        $content = $response.Content
        # Windows PowerShell returns byte[] for Actuator's vendor JSON media type.
        if ($content -is [byte[]]) { $content = [Text.Encoding]::UTF8.GetString($content) }
        if ($Health) { return (($content | ConvertFrom-Json).status -eq 'UP') }
        return ($content -match '<app-root[ >]')
    } catch { return $false }
}

function Open-LauncherBrowser { param([string]$Url) Start-Process $Url | Out-Null }

function Start-QLCStream {
    param([string]$Root, [switch]$NoBrowser, [int]$TimeoutSeconds = 180)
    $lock = $null
    try {
        $Root = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
        # Serialize launches from this folder, including different processes.
        try { $lock = [System.IO.File]::Open((Join-Path $Root '.qlcstream-start.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
        catch { throw 'OUTRA_EXECUCAO: Aguarde o inicializador desta pasta terminar e tente novamente.' }
        Write-Host '[1/5] Verificando Docker, Compose e Docker Engine...'
        if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
            throw 'DOCKER_AUSENTE: Instale Docker Desktop manualmente, abra-o e tente novamente.'
        }
        if ((Invoke-LauncherDocker @('compose', 'version')).Code -ne 0) {
            throw 'COMPOSE_INDISPONIVEL: Habilite ou atualize o Compose v2 no Docker Desktop e tente novamente.'
        }
        if ((Invoke-LauncherDocker @('info')).Code -ne 0) {
            throw 'DOCKER_INDISPONIVEL: Abra Docker Desktop, aguarde o Engine Linux ficar pronto e tente novamente. Confira o contexto e as permissoes do Docker.'
        }
        $envPath = Join-Path $Root '.env'
        if (-not (Test-Path -LiteralPath $envPath)) {
            [System.IO.File]::Copy((Join-Path $Root '.env.example'), $envPath, $false)
            Write-Host 'Configuracao inicial criada. Credenciais opcionais podem ser preenchidas em Configurar no aplicativo.'
        }
        $compose = @('compose', '--project-directory', $Root, '--env-file', $envPath, '-f', (Join-Path $Root 'compose.yaml'))
        $result = Invoke-LauncherDocker ($compose + @('config', '--format', 'json'))
        if ($result.Code -ne 0) { throw 'CONFIGURACAO: Revise a sintaxe de .env e compose.yaml localmente. Nenhum valor foi exibido.' }
        $config = $result.Text | ConvertFrom-Json
        $project = $config.name
        if (-not $project) { throw 'CONFIGURACAO: Compose nao informou o nome do projeto. Atualize Compose v2.' }
        Write-Host '[2/5] Conferindo instalacao existente e portas...'
        $result = Invoke-LauncherDocker ($compose + @('ps', '--all', '--format', 'json'))
        if ($result.Code -ne 0) { throw 'INICIALIZACAO: Nao foi possivel consultar os conteineres. Confira Docker Desktop e tente novamente.' }
        $containers = @()
        if ($result.Text.Trim()) {
            if ($result.Text.TrimStart().StartsWith('[')) { $containers = @($result.Text | ConvertFrom-Json) }
            else { $containers = @($result.Text -split "`n" | ForEach-Object { $_ | ConvertFrom-Json }) }
        }
        foreach ($container in $containers) {
            $identity = Invoke-LauncherDocker @('inspect', '--format', '{{json .Config.Labels}}', $container.ID)
            $labels = $null
            if ($identity.Code -eq 0) { $labels = $identity.Text | ConvertFrom-Json }
            if (-not $labels -or $labels.'com.docker.compose.project.working_dir'.TrimEnd('\', '/') -ne $Root) {
                throw 'OUTRA_INSTALACAO: Este nome de projeto pertence a outra pasta. Use a pasta original ou um projeto Compose isolado; nenhum conteiner foi alterado.'
            }
        }
        foreach ($service in $config.services.PSObject.Properties) {
            foreach ($port in $service.Value.ports) {
                if (-not $port.published) { continue }
                $owned = @($containers | Where-Object { $_.Service -eq $service.Name -and $_.State -eq 'running' } | ForEach-Object {
                    $_.Publishers | Where-Object { $_.PublishedPort -eq [int]$port.published -and $_.TargetPort -eq $port.target -and $_.Protocol -eq $port.protocol }
                }).Count -gt 0
                $address = $port.host_ip
                if (-not $address) { $address = '0.0.0.0' }
                if (-not $owned -and -not (Test-LauncherPort $address ([int]$port.published) $port.protocol)) {
                    throw "PORTA_OCUPADA: Porta $($port.published)/$($port.protocol) indisponivel. Feche o servico conflitante por sua interface ou revise as portas do Compose. Nenhum processo foi encerrado."
                }
            }
        }
        # Probe only local UI/API URLs, never credentials or service URLs.
        $backendPort = @($config.services.backend.ports | Where-Object { $_.target -eq 8080 -and $_.protocol -eq 'tcp' })[0].published
        $frontendPort = @($config.services.frontend.ports | Where-Object { $_.target -eq 80 -and $_.protocol -eq 'tcp' })[0].published
        if (-not $backendPort -or -not $frontendPort) { throw 'CONFIGURACAO: Backend e interface precisam publicar suas portas locais.' }
        $healthUrl = "http://127.0.0.1:$backendPort/actuator/health"
        $appUrl = "http://127.0.0.1:$frontendPort"
        $missing = @()
        foreach ($service in $config.services.PSObject.Properties) {
            if (-not $service.Value.build) { continue }
            $image = $service.Value.image
            if (-not $image) { $image = "$project-$($service.Name)" }
            if ((Invoke-LauncherDocker @('image', 'inspect', '--format', '{{.Id}}', $image)).Code -ne 0) { $missing += $service.Name }
        }
        if ($missing.Count) {
            Write-Host '[3/5] Primeira preparacao: compilando imagens ausentes (pode levar varios minutos)...'
            if ((Invoke-LauncherDocker ($compose + @('build') + $missing)).Code -ne 0) {
                throw 'BUILD: A compilacao falhou. Confira rede, espaco em disco e Docker Desktop; execute iniciar-qlcstream.bat novamente. Consulte docs/inicializacao-windows.md para diagnostico local.'
            }
        } else { Write-Host '[3/5] Imagens existentes preservadas; sem recompilacao.' }
        $services = @($config.services.PSObject.Properties.Name | Where-Object { $_ -ne 'setup-directories' })
        $running = @($containers | Where-Object { $_.State -eq 'running' } | ForEach-Object { $_.Service })
        if (@($services | Where-Object { $_ -notin $running }).Count -gt 0) {
            Write-Host '[4/5] Iniciando servicos; reutilizando conteineres e volumes...'
            if ((Invoke-LauncherDocker ($compose + @('up', '-d', '--no-build', '--no-recreate', '--pull', 'missing'))).Code -ne 0) {
                throw 'INICIALIZACAO: Compose nao conseguiu iniciar os servicos. Confira portas, rede, espaco e estado no Docker Desktop; tente novamente. Dados e volumes foram preservados.'
            }
        } else { Write-Host '[4/5] Servicos ja iniciados; nenhum conteiner foi recriado.' }
        Write-Host '[5/5] Aguardando saude do backend e resposta da interface...'
        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            if ((Test-LauncherHttp $healthUrl -Health) -and (Test-LauncherHttp $appUrl)) {
                Write-Host "Pronto: $appUrl . Abra Configurar para revisar as integracoes."
                if (-not $NoBrowser) {
                    try { Open-LauncherBrowser $appUrl } catch { Write-Host "Navegador indisponivel. Abra $appUrl manualmente." }
                }
                return 0
            }
            Start-Sleep -Seconds 2
        } while ([DateTime]::UtcNow -lt $deadline)
        throw 'SAUDE: Tempo esgotado aguardando backend/interface. Confira o estado dos servicos e o guia de recuperacao; execute iniciar-qlcstream.bat novamente. O navegador nao foi aberto.'
    } catch {
        $message = [string]$_.Exception.Message
        if ($message -match '^(DOCKER_AUSENTE|COMPOSE_INDISPONIVEL|DOCKER_INDISPONIVEL|CONFIGURACAO|OUTRA_EXECUCAO|OUTRA_INSTALACAO|PORTA_OCUPADA|BUILD|INICIALIZACAO|SAUDE):') { Write-Host "[ERRO] $message" }
        else { Write-Host '[ERRO] INICIALIZACAO: Falha ao preparar o aplicativo. Confira acesso a pasta, .env.example e Compose. Consulte docs/inicializacao-windows.md e tente novamente.' }
        return 1
    } finally { if ($lock) { $lock.Dispose() } }
}
