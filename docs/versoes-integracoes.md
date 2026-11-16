# Versões das integrações — Issue #20

Investigação iniciada em 13/11/2026, a partir da `develop` em `63c0daf` (PR #34 integrada), preservada no commit `671246a`. O inventário abaixo descreve o estado anterior à fixação. A matriz e as evidências desta entrega estão nas seções seguintes.

## Referências anteriores do checkout (inventário inicial)

| Componente | Referência atual | Situação inicial |
| --- | --- | --- |
| Prowlarr | `lscr.io/linuxserver/prowlarr:latest` | Precisa de tag específica/digest verificado e prova da API real. |
| qBittorrent | `qbittorrentofficial/qbittorrent-nox:latest` | Precisa de tag específica/digest e prova dos contratos reais. |
| PostgreSQL | `postgres:18.6-alpine` | Versão de aplicação identificada; tag ainda pode mudar. |
| Preparação de diretórios | `alpine:3.23` | Tag de linha menor; inventariar digest antes da matriz final. |
| Build do backend | `maven:3.9.12-eclipse-temurin-21-alpine` | Referência do Dockerfile; inventariar separadamente das integrações. |
| Runtime do backend | `eclipse-temurin:21-jre-alpine` | Tag de linha Java; sem digest no checkout. |
| Build do frontend | `node:26-alpine` | Tag de linha Node; sem digest no checkout. |
| Runtime do frontend | `nginx:1.29-alpine` | Tag de linha menor; sem digest no checkout. |
| APIs simuladas das fixtures | `python:3.13-alpine` | Dependência de validação; não é integração de produção. |

As fixtures `access-compose.yaml` e `storage-compose.yaml` também usam qBittorrent `latest`; precisam acompanhar a referência selecionada para evitar validar outra imagem. Fixtures de banco e preparação devem permanecer separadas da instalação diária.

## Imagens locais observadas

Somente metadados de imagens públicas já presentes no cache foram inspecionados; nenhum contêiner da instalação diária foi consultado ou alterado. Estes digests são os reportados pelo cache local, ainda sem verificação de disponibilidade/manifests do registry para a entrega:

| Imagem | Identificação observada | Evidência |
| --- | --- | --- |
| Prowlarr | `2.7.0.5723-ls168` | Label OCI e `build_version`; a versão não foi conferida pela API nesta investigação. |
| qBittorrent | Imagem `5.3.0-2`, binário `v5.3.0` | Label OCI e `/usr/bin/qbittorrent-nox --version` em contêiner descartável sem rede e sem volumes, com `--pull never`. |

Referências observadas:

```text
lscr.io/linuxserver/prowlarr@sha256:6660787a99b2e58203539583663c973b48979fc00ff2cb78dc0ea17429e1f6ef
qbittorrentofficial/qbittorrent-nox@sha256:43b2d352cf76330a7ca34c7320b9b74227d6f31e662e0e123d0457206b09a7ea
```

O Compose atual passa `QBT_VERSION=5.1.4` ao contêiner, mas usa `image: ...:latest`. O [exemplo oficial do qBittorrent](https://github.com/qbittorrent/docker-qbittorrent-nox#environment-variables) usa `QBT_VERSION` para selecionar a **tag da imagem** no host. O exemplo `docker run` não a envia como variável do contêiner. A divergência entre `5.1.4` no Compose e o binário local `5.3.0` confirma que a configuração atual não fixa a versão executada. A remoção desse parâmetro e a referência substituta serão feitas junto da validação, em alteração própria.

A [documentação LinuxServer do Prowlarr](https://docs.linuxserver.io/images/docker-prowlarr/#version-tags) diferencia os canais `latest`, `develop` e `nightly`. O canal estável não substitui uma referência reproduzível da imagem testada.

## Contratos que a matriz deve comprovar

| Integração | Chamadas identificadas no código | Prova ainda necessária com imagem selecionada |
| --- | --- | --- |
| qBittorrent | Login; versão; preferências; lista; inclusão; stop/start/recheck; remoção com `deleteFiles=false`. | Autenticação correta/recusada, respostas reais da inclusão JSON/202, controle de transferência sintética e preservação de arquivos. |
| Prowlarr | `/api/v1/system/status` e `/api/v1/search` com API key. | Versão, autenticação, busca controlada com fonte de teste própria e resultado sem segredos. |
| TMDB | API remota do catálogo e `/configuration`. | Manter testes simulados e registrar limites da validação externa; não há imagem TMDB no Compose. |

Os testes atuais do backend cobrem respostas qBittorrent 200/`Ok.`, 204 e inclusão JSON/202 por simulação. Isso é evidência dos clientes, não certificação de uma imagem. A observação de `--version` também não comprova compatibilidade da API.

## Etapas planejadas no inventário inicial

1. Verificar tags/digests e arquiteturas no registry; escolher candidatos coerentes com os contratos já implementados.
2. Preparar fixture com nomes, portas, credenciais fictícias e volumes próprios; preservar configurações e bytes sintéticos entre execuções. Não copiar dados ou credenciais pessoais.
3. Executar a matriz em instalação limpa e em cópia da configuração **sintética** anterior. Qualquer prova de transferência da Issue #20 deve usar somente conteúdo próprio em ambiente isolado; nenhuma transferência foi iniciada nesta investigação.
4. Substituir referências flutuantes e remover o parâmetro enganoso somente com os resultados registrados. Acrescentar verificação de divergência entre referência esperada e versão observada.
5. Documentar promoção explícita, cópia de segurança e recuperação. Não aplicar imagens novas à instalação diária ou promover `main` automaticamente.

No commit inicial `671246a`, nenhum Dockerfile ou Compose havia sido modificado e não havia prova de download/atualização. Essa observação histórica é substituída, para o estado desta branch, pela matriz abaixo. A Issue permanece aberta para revisão.

## Referências selecionadas

Manifests oficiais verificados em 13/11/2026 com `docker buildx imagetools inspect <referência>`. Os digests abaixo identificam **índices multiplataforma**, não IDs locais de imagem. O checkout fixa tag **e** digest: o digest determina os bytes; a tag mantém a revisão legível.

| Serviço | Tag / aplicação | Digest do índice |
| --- | --- | --- |
| qBittorrent | `qbittorrentofficial/qbittorrent-nox:5.3.0-2` / `5.3.0` | `sha256:43b2d352cf76330a7ca34c7320b9b74227d6f31e662e0e123d0457206b09a7ea` |
| Prowlarr | `lscr.io/linuxserver/prowlarr:2.7.0.5723-ls168` / `2.7.0.5723` | `sha256:6660787a99b2e58203539583663c973b48979fc00ff2cb78dc0ea17429e1f6ef` |
| Preparação | `alpine:3.23` | `sha256:469c9ea410b9215eaee7976ba6c0639039b444be8e5d638f7abbada189971eb2` |
| Banco | `postgres:18.6-alpine` | `sha256:9ba53c7d41ea093baf5c1ba635ba5434c4f979880527d49bb7d9237d3193085b` |
| Fixtures | `python:3.13-alpine` | `sha256:9ef1a599f863231c3d91e941389bfdc4a2d6070557243d54df1a4e57015b960e` |

As candidatas estabilizam os contratos já entregues, inclusive inclusão qBittorrent 5.3, sem introduzir atualização de aplicação. O cache foi ponto de partida; disponibilidade no registry, versão pela API e comportamento real foram conferidos separadamente.

| Integração | Manifest Linux/amd64 testado | Outras plataformas no índice, sem certificação local |
| --- | --- | --- |
| qBittorrent | `sha256:5c859d65d776fe9e0c8ecdf3d42afaeddc7e3cdcb560a630c14e4256c77b64e5` | 386, arm/v6, arm/v7, arm64, riscv64 |
| Prowlarr | `sha256:697220fbf427eced70569f49000450c65087c701e83d918e807cdb571c06551b` | arm64; entradas `unknown/unknown` são atestações |

Escopo: integrações, banco e preparação no Compose e imagens correspondentes de todas as fixtures ficam fixados. Python fica fixado para reproduzir os testes. Maven, Temurin, Node e Nginx dos Dockerfiles mantêm as referências inventariadas; estabilizar toda a cadeia de build/runtime exigiria matriz própria. O build completo ainda depende desses registries e dos repositórios de dependências. Não houve atualização geral de bibliotecas. TMDB é uma API remota, sem imagem local para fixar.

`QBT_VERSION` foi removida do ambiente: o exemplo oficial usa essa variável na interpolação da imagem e o entrypoint selecionado não a consome. `QBT_LEGAL_NOTICE`, `QBT_WEBUI_PORT` e `QBT_TORRENTING_PORT` continuam consumidos. `stop_grace_period: 30m` segue a orientação oficial para evitar interrupção prematura da parada do motor; é limite máximo, não espera obrigatória. UID, grupos e caminhos persistentes não foram trocados.

## Conferir execução e divergência

Com Python 3 instalado manualmente, disponibilize no **ambiente do processo** `QBITTORRENT_BASE_URL`, `QBITTORRENT_USERNAME`, `QBITTORRENT_PASSWORD`, `PROWLARR_BASE_URL` e `PROWLARR_API_KEY` e execute:

```text
python scripts/check-integration-versions.py
```

O comando não lê `.env`, não salva credenciais nem imprime endereços, corpos remotos ou exceções. Consulta login + `/api/v2/app/version` e `/api/v1/system/status`. Retorna serviço, versão esperada, observada e `match`/`mismatch`; falha de consulta produz `check_failed`. Divergência ou falha retorna código 1. Use HTTPS fora de rede local confiável e evite senhas em linhas de comando/capturas.

Redirecionamentos HTTP são recusados em todas as chamadas, inclusive login, para impedir encaminhamento de chaves e cookies. Se houver um proxy com redirecionamento, informe diretamente a URL final da API. A recusa retorna `check_failed`, sem imprimir o destino ou as credenciais.

Essa leitura confere a **aplicação**, não a revisão da imagem. Consulte localmente `docker compose images` e `docker inspect --format '{{.Config.Image}} {{.Image}}' <id-do-contêiner>`; compare a referência com a tabela e o ID com `docker image inspect --format '{{.Id}}' <referência-fixada>`. Não compare ID local de configuração com digest de índice. A fixture faz essa comparação automaticamente. O inicializador usa `--no-recreate`: contêineres existentes podem continuar com outra imagem; a conferência informa divergência sem atualizar nada.

## Matriz e evidências

Validada em 13/11/2026, no Windows com Docker Desktop/Engine Linux/amd64. Requer Docker/Compose e Python previamente instalados, sem contas externas. Da raiz:

```text
python docs/validation/test-version-checker.py
python docs/validation/test-integration-versions.py
python docs/validation/test-shared-access.py
powershell.exe -NoProfile -ExecutionPolicy Bypass -File docs/validation/Test-WindowsLauncher.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File docs/validation/Test-WindowsLauncherDocker.ps1
```

| Cenário | Evidência |
| --- | --- |
| Versão/divergência | APIs reais retornaram qBittorrent `5.3.0` e Prowlarr `2.7.0.5723`; IDs das imagens conferidos. Testes controlados verificam divergência, corpo inválido, recusa, saída sem segredos e retorno do CLI. |
| qBittorrent | Autenticação correta/recusada, preferências/lista; inclusão real de URL com **HTTP 202/JSON `pending_count`** nos dois motores; stop/start/recheck; remoção com `deleteFiles=false`. Respostas antigas 200/`Ok.` e 204 continuam cobertas por simulação, não são inferidas dessa imagem. |
| Transferência | Torrent privado sem tracker; DHT/PeX/LSD desabilitados, rede interna e peer explícito. Bytes próprios, SHA-256 `d142b96363fa0c7880f054659a9cb14980a6e2f1fcfe3877952d78736a676389`; backend leu o mesmo hash e cancelamento preservou arquivos. |
| Cliente Java real | Inclusão de série pelo backend, consulta/sincronização, PAUSE/RESUME/CANCEL. O seeder permanece disponível até transferir os bytes para o `save_path` vinculado ao pedido Java; o SHA-256 desse mesmo arquivo é conferido antes e depois de CANCEL. Recheck observado diretamente na API real; sua escolha pelo fluxo de erro do Java continua coberta por regressão simulada. |
| Prowlarr | Status e busca reais; chave inválida recusada em ambos. Indexador Torznab próprio configurado pela API; resultado encontrado diretamente e por `/api/search/releases` do backend. Não certifica fontes externas. |
| Instalação/persistência | Instalação limpa com chave e adoção explícitas; parada/início; cópia offline de sete volumes para outro projeto e recriação com tag+digest. Hashes idênticos de chave, configuração dos serviços, mídia e linhas cifradas de `integration_settings`; indexador persistido continua consultável. |
| Permissões | Backend UID 100/grupo suplementar 1000, motor UID/GID 1000; `incoming` nova 100:1000:2770. Escrita/leitura cruzada, diretório/chave/link existentes preservados. Diagnóstico HTTP corretamente mantém equivalência física inconclusiva. |
| Inicializador | 83 assertivas no Windows PowerShell 5.1 e PowerShell 7. Docker real: primeira preparação, reabertura e parada/início; IDs, ambiente, banco, chave, integração gerenciada e mídia preservados. |

A preparação de chave/configurações e do indexador ocorre somente na fase `test-clean`. `test-existing` exige a chave e as integrações anteriores, além de comparar identidade e configuração do indexador com o marcador sintético persistido; não recria dados ausentes. A matriz também retém temporariamente o banco Prowlarr apenas da cópia sintética, confirma que a validação recusa sua perda e restaura o banco original da fixture. Essa injeção não é um procedimento certificado de recuperação de produção.

Os quatro testes do verificador incluem 45 combinações de status 301/302/303/307/308, destinos absolutos, relativos e entre portas, nos três caminhos autenticados. Nenhuma requisição chega ao destino do redirecionamento. Controles legítimos preservam login 200/204 com sessão por cookie e código de saída/relato sem segredos.

Execução inicial: sufixo `28bbf7f4e7`. Após corrigir os achados da revisão da PR #35, a matriz foi repetida com sufixo `2eb34a2ddc`, incluindo recusa de perda do banco Prowlarr e hash do arquivo da própria transferência Java antes/depois de CANCEL; resultados em `data/versions-validation-2eb34a2ddc/result.json`. Fixture real do inicializador: sufixo `d949292e`. Arquivos e volumes sintéticos foram mantidos localmente para inspeção e não entram no Git.

A instalação anterior sintética usa os digests capturados do antigo `latest` e mantém `QBT_VERSION=5.1.4` para demonstrar que ela não seleciona o binário. A cópia atualizada adota tag+digest. **As aplicações têm a mesma versão antes/depois**: a prova cobre fixação/recriação com dados existentes, não migração entre releases diferentes. Nenhum dado pessoal foi copiado.

O roteiro cria nomes aleatórios `qlcstream-versions-old-*`/`qlcstream-versions-copy-*`, usa arquivo de ambiente fictício explícito, não publica portas nem monta pastas pessoais. Todos os escritores param antes da cópia, preservando modos/donos. Ao terminar, remove somente seus contêineres/redes e **mantém volumes**, inclusive após falha. Não use limpeza global. O CI repete a matriz no job **Integration versions** em Linux/amd64, além de Backend/Frontend/Windows launcher. TMDB permanece com cobertura simulada; nenhuma credencial pessoal ou consulta externa foi usada.

## Testar e promover outra versão

1. Em branch própria, registre motivação, notas oficiais, mudanças de API/esquema e plataformas. Inspecione tags no registry e anote índice e manifest da plataforma. Não escolha apenas a mais recente nem altere a instalação diária.
2. Altere Compose/fixtures e expectativas do verificador. Se necessário, ajuste a configuração sintética ao novo contrato oficial. Preserve um snapshot **sintético anterior** com referência imutável; não substitua essa base pela candidata.
3. Repita autenticação correta/recusada, consultas, inclusão/controle, transferência própria, permissões, instalação limpa, reinício e cópia offline anterior→candidata. Registre códigos, versões, hashes e limites; suporte a outra plataforma exige repetição nela.
4. Execute CI e contrato do inicializador; revise diff sem dados gerados/segredos. Atualize esta matriz com evidências e limitações; promova referências pela PR revisada. Não há promoção automática da instalação pessoal ou publicação em `main`.
5. Antes de aplicar manualmente, pare escritores e guarde, em local protegido, o conjunto coerente de banco, configurações de Prowlarr/qBittorrent, chave original, configurações cifradas, `.env` e mídia necessária. Registre referências anteriores e comprove recuperação em cópia. O procedimento completo de backup/restauração pertence à Issue #21.
6. Após revisão e backup verificável, a administração pode baixar referências exatas e recriar deliberadamente os serviços escolhidos. Não execute `down -v`. Valide novamente as versões e as consultas. O inicializador diário não faz essa promoção.

## Recuperação e limites

Uma referência imutável recupera a imagem, mas **não desfaz alterações de banco, configuração ou esquema**. Reverter só o Compose e reutilizar dados migrados pode falhar ou corromper estado; não está certificado. Foi demonstrada cópia offline e preservação **na mesma versão**, sem downgrade, restauração de backup de produção ou compatibilidade de migrações entre versões.

Se a candidata falhar, mantenha a origem intacta e descarte somente a cópia sintética identificada, após inspeção. Em produção, restaure o conjunto consistente anterior, incluindo a chave correspondente, apenas conforme procedimento previamente testado. Não gere outra chave para abrir dados cifrados existentes. Ausência de backup verificado impede prometer recuperação de dados.

## Fontes oficiais consultadas em 13/11/2026

- [Imagem qBittorrent: variáveis, volumes, identidade e parada](https://github.com/qbittorrent/docker-qbittorrent-nox).
- [API WebUI 5.x](https://github.com/qbittorrent/qBittorrent/wiki/WebUI-API-%28qBittorrent-5.0%29) e [preferências na release 5.3.0](https://github.com/qbittorrent/qBittorrent/blob/release-5.3.0/src/base/preferences.cpp). A fixture usa `[WebUI]` atual, sem impor mudanças às configurações pessoais.
- [LinuxServer Prowlarr: tags, arquiteturas, PUID/PGID e persistência](https://docs.linuxserver.io/images/docker-prowlarr/) e [API oficial v1](https://prowlarr.com/docs/api/).
- [Docker: identidade por digest](https://docs.docker.com/reference/cli/docker/image/pull/#pull-an-image-by-digest-immutable-identifier), [Compose up](https://docs.docker.com/reference/cli/docker/compose/up/) e [prazo de parada](https://docs.docker.com/reference/compose-file/services/#stop_grace_period).
- Auxiliares oficiais: [PostgreSQL](https://hub.docker.com/_/postgres), [Alpine](https://hub.docker.com/_/alpine), [Python](https://hub.docker.com/_/python). Os manifests foram conferidos diretamente nos registries; os digests acima identificam a consulta.


O [procedimento de backup e restauração](backup-restauracao.md) reúne dump lógico, perfis completos, chave original em guarda separada e mídia opcional. A recuperação cria destino isolado novo, exige compatibilidade e não aplica rollback Flyway nem substitui a instalação ativa.
