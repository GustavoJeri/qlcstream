# Versões identificadas e atualização recuperável — investigação da Issue #22

Investigação iniciada em 13/11/2026 sobre `develop` em `3c7e4048d16180e590d82352e9fe8b72a8f1ef1e`, após integrar a PR #36 (Issue #21). O [CI pós-merge](https://github.com/GustavoJeri/qlcstream/actions/runs/43539592270) aprovou os cinco jobs desse commit. Este documento é um inventário e proposta inicial. **Não certifica uma release, pacote distribuível, atualização entre versões ou recuperação depois de uma migração.** Não foram criados tags, releases ou pacotes nesta etapa, nem alterada a instalação diária ou `main`.

## Estado observado no código versionado

| Área | Estado atual | Trabalho necessário |
| --- | --- | --- |
| Versão do backend | `backend/pom.xml`: `0.0.1-SNAPSHOT` | Definir uma identidade de release unificada e disponibilizá-la na execução. |
| Versão do frontend web | `frontend/package.json`: `0.0.0` | Não tratar a versão de scaffold como uma release publicada. |
| Scaffold desktop | `frontend/src-tauri/Cargo.toml` e `tauri.conf.json`: `0.1.0` | Separar esse número de scaffold da versão pública que vier a ser validada. |
| API de identidade | `/api/system/status` retorna nome, estado e horário, sem versão | Identificar a instalação sem consultar o histórico Git. |
| Dockerfiles da aplicação | Backend/frontend são construídos localmente, com bases por tag sem digest e sem identidade de release explícita nos Dockerfiles | Associar versão, commit, plataforma e imagens efetivamente produzidas ao pacote/candidato. Distinguir a reprodução do pacote da reprodução das camadas de imagem. |
| Imagens externas | Compose fixa referências e digests de PostgreSQL, Prowlarr, qBittorrent e preparação | Conservar a matriz validada; não promover outra imagem como efeito colateral do empacotamento. |
| Inicializador Windows | Reutiliza serviços/imagens, preserva `.env` e usa `--no-build --no-recreate` na retomada | Manter o uso diário separado da aplicação administrativa de uma atualização. |
| Backup/restauração | Formato 1 integrado pela PR #36, com chave separada, manifesto privado e validação na mesma identidade | Conservar a versão anterior e suas imagens; não chamar a restauração com outro código esperando compatibilidade automática. |
| Dependência de Git no backup | `compatibility()` chama `git ls-files`; a criação registra `git rev-parse HEAD` | Resolver o contrato para uma instalação extraída de pacote sem `.git` e sem presumir Git instalado. |
| CI | Cinco jobs de teste; o workflow atual não publica uma release | Acrescentar geração/inspeção do candidato e evidências sintéticas antes de qualquer publicação. |
| Histórico do fork | `CHANGELOG.md` registra incrementos por data | Preparar notas por versão, com requisitos, matriz, exclusões e limites de recuperação. |

Há também scaffold Tauri em `frontend/src-tauri`. Ele precisa ser inventariado separadamente: a existência de configuração ou de scripts `desktop:build` não comprova um instalador Windows distribuído. A primeira solução deve permanecer no fluxo Docker/Windows já validado, sem ampliar automaticamente para outro canal de entrega.

Na consulta inicial ao GitHub, `gh release list` e a listagem de tags não retornaram versões. Isso não define qual será o número da primeira release; a conferência deve ser repetida imediatamente antes de criar uma candidata ou tag.

## Contrato inicial proposto

1. Definir uma única versão pública, distinguindo candidata de versão publicada. A proposta inicial é versionamento semântico com sufixo de candidata; nenhum número ou tag foi reservado nesta investigação.
2. Gerar um manifesto de distribuição **público e sem segredos**, diferente do manifesto privado de backup. Relacionar versão, commit, arquivos permitidos, hashes, matriz das integrações e identidade das imagens/builds que forem de fato entregues.
3. Gerar o pacote a partir de uma lista explícita de arquivos versionados necessários. Não compactar a pasta de trabalho inteira. Excluir `.git`, `.env`, `data`, credenciais, backups, perfis, fixtures de validação, caches e saídas locais. `.env.example` deve continuar sendo apenas o modelo revisado usado na primeira preparação.
4. Disponibilizar a identidade instalada pela aplicação e/ou arquivo de distribuição validado, sem exigir consulta ao Git. Resolver em conjunto a compatibilidade do backup em instalações de pacote; não basta remover a checagem de integridade atual.
5. Separar instruções de instalação nova, uso diário, atualização administrativa e recuperação. Nunca substituir `.env`, chave, perfis ou mídia por arquivos padrão do pacote.
6. Antes de uma atualização, preservar o pacote/código e as imagens da versão anterior, além do backup consistente com a chave correspondente. Conservar a associação entre identidade da aplicação, esquema e backup.
7. Ensaiar a candidata sobre uma instalação sintética limpa e uma instalação sintética anterior identificada. Usar portas, projetos, volumes, credenciais e arquivos próprios. Comparar registros, configuração efetiva, indexador, metadados de torrents, permissões e mídia anterior.
8. Injetar uma falha controlada de atualização e recuperar em destino novo. A validação não pode refazer cadastros ou gerar outra chave para esconder perda de dados.
9. Publicar somente depois de revisar o conteúdo do pacote, seus hashes, notas, matriz e evidências. Não acoplar o lançamento público a uma atualização da instalação diária.

## Recuperação e Flyway

O contrato atual do [backup](backup-restauracao.md) exige os mesmos arquivos de execução/migrações e as imagens correspondentes. Se uma candidata mudar a identidade, a recuperação do backup anterior deve usar a versão anterior em outro destino; executar o restore com o checkout novo não é um procedimento de rollback demonstrado.

A primeira candidata deve preferir o esquema atual, sem acrescentar migrações apenas para viabilizar a distribuição. Mesmo sem uma nova migração, preservar dados e reabrir o backup com o código anterior precisa ser ensaiado. Se houver mudança de esquema, será necessário um contrato de compatibilidade e prova específicos. Não usar `flyway repair`, `baseline`, remoção do histórico ou troca da chave como recuperação automática.

Ainda não há release pública anterior listada para servir como origem de um teste entre duas releases publicadas. Um snapshot administrado de `develop` pode ser uma base inicial de teste, desde que identificado como tal, com commit/imagens explícitos. Não deve ser descrito como uma versão pública suportada que nunca existiu.

## Pendências desta investigação

As pendências registradas na investigação inicial de 13/11/2026 foram implementadas abaixo na entrega da Issue #2 (`[F04.3]`). A criação de tag pública, publicação em `main` e atualização da instalação diária continuam decisões administrativas explícitas, fora da automação desta PR.

## Contrato implementado — Issue #2 (`[F04.3]`)

### 1. Identidade de versão sem Git

- A versão pública única reside em `VERSION` (formato SemVer 2.0.0: `MAJOR.MINOR.PATCH` para versão final e sufixo `-rc.N` para candidata; candidata inicial: `0.1.0-rc.1`).
- `backend/src/main/resources/release-metadata.properties` armazena `qlcstream.release.version`, `qlcstream.release.channel` (`candidate` ou `release`) e `qlcstream.release.schema-version` (`V12`), mantidos em consistência com `VERSION`, com a maior migração Flyway em `backend/src/main/resources/db/migration/` e com os valores padrão `QLCSTREAM_VERSION` de `backend/Dockerfile`, `frontend/Dockerfile` e `compose.yaml`.
- Os números de scaffold (`backend/pom.xml`, `frontend/package.json` e `frontend/src-tauri/*`) permanecem separados da versão pública. O canal desktop Tauri (`frontend/src-tauri`) permanece fora do pacote Docker/Windows.
- Em execução, `GET /api/system/status` (com `Cache-Control: no-store`) retorna `application`, `status`, `version`, `channel`, `schemaVersion`, `commit` e `timestamp`. A página **Configurações** exibe essa versão sem exigir acesso ao Git.
- Os Dockerfiles de `backend` e `frontend` gravam os rótulos OCI `org.opencontainers.image.version` e `org.opencontainers.image.revision` (com `QLCSTREAM_RELEASE_COMMIT` sanitizado para SHA-1 hexadecimal de 40 caracteres ou `unknown`).
- Em disco (com ou sem `.git`), `python scripts/qlc-release.py inspect` informa versão, canal, esquema, commit, origem (`release-package` ou `git-checkout`) e matriz das integrações fixadas.

### 2. Empacotamento reproduzível e manifesto público

`scripts/qlc-release.py` usa Python 3.12+ apenas com a biblioteca padrão, sem instalar pacotes no host e sem ler `.env` ou `data/`.

```powershell
python scripts/qlc-release.py package --output data/release-out
python scripts/qlc-release.py verify --package data/release-out/qlcstream-0.1.0-rc.1.zip --checksums data/release-out/qlcstream-0.1.0-rc.1.sha256
```

- O empacotamento exige árvore Git limpa por padrão (flag `--allow-dirty` existe apenas para ensaios locais antes de commit). No modo padrão, os bytes são lidos diretamente dos **blobs canônicos de `HEAD`** (`git ls-tree` + `git cat-file --batch`), garantindo que checkouts limpos com LF ou CRLF gerem ZIP e manifesto com hashes SHA-256 idênticos entre sistemas operacionais.
- Os arquivos incluídos vêm de uma **lista explícita de permissão**: arquivos operacionais da raiz, `scripts/`, documentação operacional e `docs/releases/<versao>.md`, além dos arquivos de execução de `backend/` e `frontend/` (excluindo `backend/src/test/`, `frontend/src-tauri/` e `frontend/**/*.spec.ts`).
- São explicitamente recusados: `.git`, `.env`, `data/`, `docs/validation/`, `frontend/src-tauri/`, testes unitários, caches (`target/`, `node_modules/`, `dist/`, `.angular/`, `__pycache__/`), links simbólicos, `.key`, `.pem`, `.dump`, `.db`, `.sqlite*`, `.log` e padrões de segredos (chaves privadas PEM, tokens JWT/Bearer, credenciais preenchidas em `.env.example` e qualquer `POSTGRES_PASSWORD` diferente de vazio ou do valor público de exemplo `qlc_stream_local`).
- O arquivo ZIP usa ordenação determinística, permissões normalizadas e carimbo de data derivado do timestamp UTC do commit, produzindo hashes SHA-256 idênticos entre gerações do mesmo commit.
- São gerados três artefatos públicos sem segredos:
  1. `qlcstream-<versao>.zip` (contendo `RELEASE-MANIFEST.json` na raiz interna);
  2. `qlcstream-<versao>.manifest.json` (manifesto público com versão, canal, esquema, commit, matriz das imagens fixadas, hashes SHA-256 de todos os arquivos e mapa `compatibility`);
  3. `qlcstream-<versao>.sha256` (checksums SHA-256 do ZIP e do manifesto).

### 3. Compatibilidade do backup em instalações extraídas de pacote (sem `.git`)

`scripts/qlc-backup.py` foi atualizado para identificar a origem sem depender de `.git` nem de `git` instalado, **compartilhando diretamente o validador `verify_directory` de `scripts/qlc-release.py`**:

- Quando `RELEASE-MANIFEST.json` está presente na raiz (ou é link simbólico), o backup delega a validação integral ao `verify_directory` de `scripts/qlc-release.py` (formato, hashes SHA-256 de todos os arquivos, consistência entre manifesto, `VERSION`, `release-metadata.properties`, migrações Flyway, Dockerfiles, matriz do `compose.yaml`, notas de release, ausência de links simbólicos e ausência de arquivos de execução extras sob `backend/` e `frontend/`). Qualquer divergência falha fechada com `MANIFESTO_INVALIDO`, garantindo que `qlc-backup.py` e `qlc-release.py verify-dir` nunca atribuam identidades divergentes à mesma pasta.
- O mapa `compatibility` e o `commit` vêm do manifesto validado e o manifesto privado de backup (formato 1) passa a registrar também `releaseVersion`.
- Em checkouts Git, backups antigos já gerados no formato 1 pela implementação anterior (cujo mapa `compatibility` incluía `frontend/src-tauri/` e `frontend/**/*.spec.ts`) continuam sendo verificados e restaurados integralmente por meio do cálculo legado `legacyCompatibility` enviado ao `vault.py`, sem enfraquecer a exigência de correspondência exata.
- Quando nem `RELEASE-MANIFEST.json` nem `.git` existem, o comando recusa a operação com `ORIGEM_NAO_IDENTIFICADA`.

### 4. Instalação limpa, atualização administrativa e recuperação

Os passos detalhados constam nas notas da candidata em [docs/releases/0.1.0-rc.1.md](releases/0.1.0-rc.1.md). Resumo dos limites:

- **Instalação limpa:** conferir primeiro o SHA-256 do ZIP fora do pacote (`Get-FileHash` no Windows ou `sha256sum -c` no Linux/macOS) contra `.sha256`, extrair em pasta nova vazia, validar a pasta extraída com `python scripts/qlc-release.py verify-dir` e `inspect`, e iniciar com `iniciar-qlcstream.bat` (ou `docker compose up -d --build` após preparar `.env`).
- **Uso diário:** `iniciar-qlcstream.bat` continua preservando `.env`, contêineres e imagens (`--no-build --no-recreate`), sem atualizar versões implicitamente.
- **Atualização administrativa:**
  1. Preservar uma cópia da pasta/pacote da versão anterior e dos IDs das imagens locais atuais.
  2. Parar normalmente os escritores (`docker compose stop --timeout 1800 frontend backend prowlarr qbittorrent`).
  3. Executar e verificar backup consistente (`qlc-backup.py backup` + `verify`).
  4. Verificar o novo pacote e aplicá-lo sobre a pasta extraída com `python scripts/qlc-release.py apply --package <zip> --checksums <sha256> --target <pasta>`: o comando valida integralmente a instalação atual antes de gravar, recusa colisões com arquivos locais fora do manifesto anterior (`COLISAO_ARQUIVO_LOCAL`), prepara e valida a nova versão em uma área temporária, preserva `.env` e `data/`, reverte automaticamente qualquer falha durante a substituição mantendo a versão anterior intacta (e preserva a cópia de rollback informando seu caminho se a própria reversão falhar, com `REVERSAO_INCOMPLETA`), e informa `integrationsChanged` na saída JSON.
  5. Reconstruir explicitamente `backend` e `frontend` com `--build-arg QLCSTREAM_VERSION=... --build-arg QLCSTREAM_COMMIT=...`. Quando `integrationsChanged` estiver vazio (matriz inalterada), recriar `backend` e `frontend` e retomar `prowlarr` e `qbittorrent` com `docker compose start`. Se `integrationsChanged` listar serviços com digest alterado, baixar explicitamente com `docker compose pull`, recriar esses serviços (`docker compose up -d --no-build --force-recreate backend frontend <servicos-alterados>`) e iniciar explicitamente com `docker compose start` as integrações paradas no passo 2 cuja imagem permaneceu igual, seguindo [docs/versoes-integracoes.md](versoes-integracoes.md).
  6. Conferir `/actuator/health`, `/api/system/status`, `scripts/check-integration-versions.py`, IDs das imagens dos contêineres e os dados preservados.
- **Recuperação após falha:** não usar `flyway repair`, `baseline`, troca de chave ou `down -v`. Restaurar o backup pré-atualização em um destino novo e isolado usando a pasta/pacote **da versão anterior preservada** e as imagens locais anteriores com `qlc-backup.py restore`. Tentar restaurar o backup da versão anterior usando o pacote da versão nova é recusado antes de criar volumes (`VERSAO_ESQUEMA_INCOMPATIVEL`).

## Validação reproduzível

```powershell
# Contratos sem Docker (reprodutibilidade, segredos, apply seguro e backup sem .git)
python docs/validation/test-release-package.py
python docs/validation/test-backup-command.py

# Ensaio isolado completo com Docker (instalação limpa de pacote, backup sem .git, atualização v1 -> v2, falha controlada e recuperação v1)
docker build -t qlcstream-backup-helper:1 scripts/backup
python docs/validation/test-release-upgrade.py
```

O roteiro `docs/validation/test-release-upgrade.py` cria projeto e volumes sintéticos aleatórios em rede Docker interna sem portas publicadas, empacota a candidata atual e uma candidata sintética seguinte (`V12`), ensaia instalação limpa a partir do ZIP extraído sem `.git`, executa backup pré-atualização sem `.git`, aplica a atualização preservando `.env`, `data/`, banco, chave, configurações gerenciadas, indexador Prowlarr, metadados do qBittorrent e mídia própria, confirma recusa de restore incompatível pela versão nova e, após falha controlada pós-atualização, recupera integralmente o estado anterior usando o pacote preservado sem `.git`. Apenas o resumo sintético sem segredos vai para `data/release-validation-*/result.json` (ignorado pelo Git).

## Evidências locais — 19/11/2026

- **Backend (`./mvnw verify`):** 87 testes executados, 0 falhas, 0 erros (7 testes POSIX ignorados no Windows), incluindo `ApplicationModulesTest` e 3 testes novos em `SystemStatusControllerTest` (contrato `/api/system/status` com `no-store`, consistência entre `VERSION`, `release-metadata.properties` e migração `V12`, e sanitização de `QLCSTREAM_RELEASE_COMMIT`).
- **Frontend (Node 26 em contêiner `node:26-alpine`):** 12 arquivos de teste, 50 testes aprovados e build de produção concluído sem novos avisos.
- **Contratos sem Docker:** `test-release-package.py` (4 testes: reprodutibilidade bit a bit do ZIP/manifesto, bloqueio de arquivos proibidos/segredos, `inspect`/`verify-dir`/backup sem `.git`, `apply` preservando `.env` e `data/`, recusa de árvore suja e diretórios não gerenciados; 1 teste POSIX de symlinks ignorado no Windows e executado no CI Linux), `test-backup-command.py` (10 testes aprovados integralmente em Linux POSIX; 9 aprovados e 1 POSIX ignorado no Windows), `test-backup-package.py` em Linux (5 testes aprovados), `test-version-checker.py` (4 testes aprovados) e `Test-WindowsLauncher.ps1` (83 assertivas aprovadas).
- **Ensaio isolado real no Docker (`test-release-upgrade.py`, execução `685121de89`, Docker Engine 29.8.0):**
  - Empacotamento e verificação da candidata `0.1.0-rc.1` (sem `.git`/`.env`/`data/`/`src-tauri`/`validation`).
  - Instalação limpa a partir do pacote extraído sem `.git`, rótulos OCI conferidos nas imagens de backend/frontend, interface Nginx (`<app-root>`) + `/api/system/status` confirmando `0.1.0-rc.1`, canal `candidate`, esquema `V12` e commit, e `scripts/check-integration-versions.py` empacotado confirmando Prowlarr e qBittorrent fixados.
  - Backup pré-atualização com mídia e `verify` executados a partir da pasta extraída sem `.git`.
  - Atualização administrativa `0.1.0-rc.1` → `0.1.0-rc.2` (candidata sintética `V12`) preservando `.env`, `data/`, registros do banco, chave original, configurações gerenciadas cifradas, indexador Prowlarr, metadados do qBittorrent e bytes de mídia.
  - Recusa da restauração do backup `0.1.0-rc.1` pelo pacote `0.1.0-rc.2` antes da criação de qualquer volume (`VERSAO_ESQUEMA_INCOMPATIVEL`).
  - Falha controlada pós-atualização recuperada integralmente para `0.1.0-rc.1` em destino isolado novo usando o pacote preservado sem `.git` e as imagens anteriores. Evidência sintética local em `data/release-validation-685121de89/result.json` (ignorada pelo Git).

Referências: [Issue #2 (upstream)](https://github.com/diegodinizm1/qlcstream/issues/2), [Issue #22 (fork)](https://github.com/GustavoJeri/qlcstream/issues/22), [notas da candidata 0.1.0-rc.1](releases/0.1.0-rc.1.md), [matriz das integrações](versoes-integracoes.md), [backup/restauração](backup-restauracao.md) e [inicializador Windows](inicializacao-windows.md).
