# Inicialização no Windows — Issue #19

Com Docker Desktop instalado manualmente e aberto em modo Linux, dê dois cliques em **`iniciar-qlcstream.bat`** na raiz do projeto. Ele usa Windows PowerShell 5.1, incluído no Windows, e Compose v2. Não requer Java ou Node no host. O navegador abre somente depois de o backend responder `UP` e a interface responder com a página do aplicativo. O endereço padrão é `http://127.0.0.1:4200`.

O inicializador é versionado junto de `scripts/Start-QLCStream.ps1` e `scripts/WindowsLauncher.ps1`. Utilitários particulares chamados `iniciar.bat`, `parar.bat` ou `aplicar-chaves.bat` não compõem esse contrato e podem ter outro comportamento.

## Contrato

- **Primeira preparação:** verifica Docker, Compose e Engine; cria `.env` copiando `.env.example` somente se ausente; confere portas; compila apenas as imagens de serviços com `build` que ainda não existem; baixa imagens de dependências somente quando ausentes; inicia em segundo plano.
- **Uso diário e retomada:** usa as imagens locais e `up -d --no-build --no-recreate --pull missing` quando há serviços parados/ausentes. Reutiliza os contêineres e volumes. Depois de reiniciar o computador e abrir Docker Desktop, execute o mesmo arquivo.
- **Aplicação já iniciada:** confirma a pasta proprietária dos contêineres pelas etiquetas do Compose e reconhece as portas publicadas por seus próprios serviços. Quando todos estão rodando, não executa `up`. Aguarda backend/interface antes de abrir o navegador. Uma instalação com o mesmo nome de projeto em outra pasta é recusada sem alterações.
- **Execução repetida/concomitante:** um bloqueio exclusivo por pasta impede dois inicializadores concorrentes; o arquivo de bloqueio vazio permanece, mas o bloqueio é liberado ao terminar. Pode tentar novamente após uma falha. Caminhos com espaços e execução a partir de outra pasta são suportados.
- **Credenciais:** a falta de TMDB, Prowlarr ou qBittorrent não bloqueia a abertura. Abra **Configurar** para seguir o [assistente](assistente-primeira-configuracao.md). Criar a chave de proteção e salvar integrações continuam sendo ações explícitas na tela. O catálogo depende apenas de sua própria integração.

O `.env`, configurações de serviços, banco, chave e mídia existentes não são substituídos. O inicializador não instala requisitos, não mata processos, não apaga volumes, não migra segredos e não inicia downloads. A preparação de diretórios continua a cargo do script já existente no Compose; destinos de mídia existentes não são corrigidos silenciosamente.

**Atualizações são administrativas:** código, Dockerfiles ou `.env` alterados não provocam rebuild/recriação automática de contêineres existentes. Revise e faça backup antes de aplicar mudanças deliberadamente. Para compilar uma atualização revisada: `docker compose build backend frontend`; para aplicá-la: `docker compose up -d`. Para aplicar variáveis novas somente ao backend: `docker compose up -d --no-deps --force-recreate backend`. Esses comandos podem recriar serviços; não fazem parte da inicialização diária. Não use `down -v` para recuperar uma falha.

As imagens externas do Compose estão fixadas por digest. O inicializador pode preservar um contêiner criado com outra referência; use a [conferência de versão e identidade](versoes-integracoes.md#conferir-execução-e-divergência) antes de uma atualização administrativa. A matriz comprova recriação na mesma versão sobre cópia sintética; não promete desfazer migrações de dados.

Para parar preservando os contêineres e volumes: `docker compose stop`, na raiz. O inicializador pode reabri-los depois.

## Falhas e recuperação

| Mensagem | Ação |
| --- | --- |
| `DOCKER_AUSENTE` | Instale Docker Desktop manualmente e reabra o inicializador. |
| `COMPOSE_INDISPONIVEL` | Habilite/atualize Compose v2 no Docker Desktop. |
| `DOCKER_INDISPONIVEL` | Abra Docker Desktop e aguarde Engine Linux. Confira permissões e contexto ativo. |
| `PORTA_OCUPADA` | A mensagem informa número e TCP/UDP. Revise o serviço conflitante pela sua interface ou ajuste o Compose conscientemente. O inicializador não encerra processos. |
| `OUTRA_INSTALACAO` | Abra a pasta proprietária do projeto existente. Para testes, use nome de projeto, portas, dados e volumes próprios. |
| `OUTRA_EXECUCAO` | Aguarde o outro inicializador terminar. Se persistir, confira acesso de escrita à pasta. |
| `CONFIGURACAO` | Revise localmente `.env`/Compose; preserve os arquivos existentes. |
| `BUILD` | Confira rede, disco e Docker Desktop; repita. Imagens já disponíveis são reaproveitadas. |
| `INICIALIZACAO` | Confira estado dos serviços, portas, rede, montagens e espaço no Docker Desktop; repita após corrigir. Serviços parcialmente iniciados são preservados. |
| `SAUDE` | Backend/interface não ficaram prontos no prazo. Confira serviços, banco e a chave correspondente antes de repetir. Nenhum navegador é aberto após timeout. |

São verificadas todas as portas publicadas no Compose efetivo, incluindo 4200, 8080, 9696, 8081 e 6881 TCP/UDP no arquivo padrão. A disponibilidade é uma verificação pontual: outro processo pode ocupar uma porta entre a conferência e o `up`; nesse caso a falha é informada como `INICIALIZACAO`. Não se promete liberar portas reservadas pelo Windows.

Saídas nativas do Docker e corpos HTTP ficam em memória e não são exibidos nem gravados em logs pelo inicializador. A compilação pode demorar sem saída detalhada; as cinco etapas indicam o progresso. Para investigação administrativa local, consulte `docker compose ps -a` e, se necessário, logs/build no Docker Desktop ou terminal. Eles podem conter segredos (inclusive a senha temporária do motor): não compartilhe saídas brutas, `docker compose config`, `.env` ou capturas com credenciais.

No terminal, é possível usar `iniciar-qlcstream.bat -NoBrowser -TimeoutSeconds 300`. O prazo padrão de 180 segundos vale para a espera HTTP depois do `up`; não limita downloads de imagens, build ou o comando Compose. Retorno 0 indica prontidão; 1 indica falha. A política de execução é aplicada somente ao processo iniciado pelo BAT; políticas corporativas podem exigir autorização administrativa para scripts.

Semântica de preservação: [documentação do Compose up](https://docs.docker.com/reference/cli/docker/compose/up/).

## Validação reproduzível

Execute na raiz:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File docs/validation/Test-WindowsLauncher.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File docs/validation/Test-WindowsLauncherDocker.ps1
```

O primeiro roteiro não usa Docker real: simula comandos, respostas HTTP e abertura do navegador, mas usa arquivos, bloqueio e sockets Windows reais. O segundo requer Docker Desktop e executa o BAT público numa pasta de teste com espaços, em um projeto aleatório com PostgreSQL, backend e interface reais e sem integrações externas. Os volumes de teste e arquivos sintéticos ficam preservados para revisão; ao terminar, remove somente contêineres/redes desse projeto. Não monta dados pessoais nem inicia downloads. A rede interna do banco é separada da rede que publica API/interface.

Reiniciar somente os serviços da fixture comprova retomada após parada; não equivale a reiniciar Windows ou Docker Desktop. A abertura real do navegador e a interoperabilidade com integrações externas não são cobertas pelo smoke test automático.

### Evidências de 13/11/2026

- **83 verificações aprovadas em cada versão**, Windows PowerShell 5.1 e PowerShell 7. Docker ausente, Compose indisponível, Engine desligado, portas TCP/UDP ocupadas, erro de configuração, falha de build, falha no `up`, timeout, outra instalação e execução concorrente foram controlados na suíte. A captura nativa de stdout/stderr, sockets ocupados/livres e bloqueio de arquivo foram exercitados no Windows. O navegador foi substituído por um observador, que confirmou a abertura somente após ambas as respostas válidas.
- **BAT público com Docker Desktop/Engine 29.8.0:** aprovado em pasta com espaços e invocado de outra pasta. A primeira preparação criou o `.env` sintético e compilou backend/interface aproveitando cache local. Repetição reconheceu serviços e portas próprios, sem `up` ou rebuild. Após `stop`, a retomada preservou IDs de contêineres/imagens, bytes do `.env`, linha sintética no PostgreSQL, arquivo de mídia, hash da chave e integração gerenciada fictícia. O primeiro acesso à API de configuração funcionou sem credenciais e sem chave; a chave foi criada explicitamente pelo roteiro para testar sua preservação.
- O smoke test inicialmente identificou uma limitação da rede interna da fixture e depois a resposta Actuator em `byte[]` no PowerShell. A rede foi corrigida e a leitura recebeu regressão; a execução final passou. As fixtures foram encerradas sem remover volumes sintéticos.
- Não foram reiniciados Windows ou Docker Desktop, nem testadas contas externas ou abertura visual real do navegador. As falhas de Docker/build foram simuladas; não foi provocada indisponibilidade do Docker compartilhado. Nenhum backend/frontend foi alterado e nenhum download foi iniciado. Instalação diária, arquivos de configuração e dados pessoais não foram usados.
