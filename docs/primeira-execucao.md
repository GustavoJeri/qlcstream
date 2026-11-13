# Primeira execução

Para uma instalação nova pelo Compose, inicie os serviços e abra **Configurar**. O [assistente por etapas](assistente-primeira-configuracao.md) permite criar a proteção no servidor, testar e salvar as integrações sem editar credenciais em arquivos. Configurações existentes por ambiente são preservadas. O guia manual abaixo continua disponível para instalações administradas por ambiente.

## Configuração manual por ambiente

O assistente de configuração ainda está no [plano de evolução](roadmap.md). Hoje é necessário configurar três integrações: TMDB para o catálogo, Prowlarr para busca de fontes e qBittorrent para downloads.

## 1. Preparar os requisitos

Instalar e abrir o Docker Desktop. Pelo Docker, não é necessário instalar Java ou Node separadamente no computador.

Na raiz do projeto, criar `.env` a partir de `.env.example` somente se ainda não existir. No PowerShell:

```powershell
if (!(Test-Path -LiteralPath .env)) { Copy-Item -LiteralPath .env.example -Destination .env }
```

Obter o token de leitura da API do [TMDB](https://www.themoviedb.org/settings/api) e preencher `TMDB_API_TOKEN` no `.env`. Não publicar esse arquivo.

## 2. Iniciar os serviços

```text
docker compose up -d --build
```

Endereços no navegador:

| Serviço | Endereço |
|---|---|
| QLC Stream | http://localhost:4200 |
| qBittorrent | http://localhost:8081 |
| Prowlarr | http://localhost:9696 |

## 3. Conectar as integrações

No primeiro acesso ao Prowlarr, concluir a configuração de acesso, selecionar as fontes desejadas e obter a chave da API. Preencher `PROWLARR_API_KEY` no `.env`.

No qBittorrent, usar as credenciais definidas na própria instalação. Quando a imagem gerar uma senha temporária, ela estará nos registros locais do serviço:

```text
docker compose logs --tail=100 qbittorrent
```

Concluir a configuração de acesso na interface do qBittorrent e preencher `QBITTORRENT_USERNAME` e `QBITTORRENT_PASSWORD` no `.env`. Não compartilhar os registros que contenham a senha temporária.

Para o backend executado pelo Compose deste projeto, os endereços internos são:

```dotenv
PROWLARR_BASE_URL=http://prowlarr:9696
QBITTORRENT_BASE_URL=http://qbittorrent:8080
```

Esses endereços são usados entre contêineres. Os endereços `localhost` da tabela são para o navegador no computador. Se o backend for executado fora do Docker, sua configuração de rede deverá ser ajustada para esse modo.

## 4. Aplicar as credenciais

Depois de salvar `.env`, recriar o backend para carregar as variáveis:

```text
docker compose up -d --no-deps --force-recreate backend
```

Abrir o aplicativo e conferir catálogo, busca de fontes e um download de conteúdo que possa ser usado para teste.

A tela atual testa autenticação e conectividade. Como alternativa às credenciais por ambiente, a API oferece [configuração persistente sem recriar o backend](configuracao-integracoes.md). O [assistente visual](assistente-primeira-configuracao.md) oferece a alternativa por etapas.

## Arquivos e uso diário

Por padrão, os arquivos ficam em `data/media`, compartilhados como `/data` no backend e `/downloads` no qBittorrent. Alterações desse compartilhamento precisam manter ambos os serviços apontando para os mesmos arquivos.

Depois da configuração inicial, usar `docker compose up -d` para iniciar novamente e `docker compose stop` para parar. Cancelar um download no aplicativo mantém os arquivos já salvos.

O projeto usa imagens `latest` para algumas integrações. Fixar versões verificadas e facilitar as atualizações faz parte do incremento F04; isso ainda não foi alterado nesta entrega.
