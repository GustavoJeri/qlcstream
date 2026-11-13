# Inventário inicial de versões — Issue #20

Investigação iniciada em 13/11/2026, a partir da `develop` em `63c0daf` (PR #34 integrada). Este documento registra a base para a matriz de compatibilidade. **Ainda não declara versões suportadas nem promove alterações no Compose.**

## Referências do checkout

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

## Trabalho seguinte

1. Verificar tags/digests e arquiteturas no registry; escolher candidatos coerentes com os contratos já implementados.
2. Preparar fixture com nomes, portas, credenciais fictícias e volumes próprios; preservar configurações e bytes sintéticos entre execuções. Não copiar dados ou credenciais pessoais.
3. Executar a matriz em instalação limpa e em cópia da configuração **sintética** anterior. Qualquer prova de transferência da Issue #20 deve usar somente conteúdo próprio em ambiente isolado; nenhuma transferência foi iniciada nesta investigação.
4. Substituir referências flutuantes e remover o parâmetro enganoso somente com os resultados registrados. Acrescentar verificação de divergência entre referência esperada e versão observada.
5. Documentar promoção explícita, cópia de segurança e recuperação. Não aplicar imagens novas à instalação diária ou promover `main` automaticamente.

Nenhum Dockerfile, Compose, credencial ou volume foi modificado nesta etapa. Não foram executados testes de download ou atualização; a Issue #20 permanece aberta.
