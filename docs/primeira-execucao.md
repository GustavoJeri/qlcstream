# Primeira execução

No Windows, instale e abra Docker Desktop em modo Linux e execute **`iniciar-qlcstream.bat`** na raiz. Aguarde as etapas: o navegador abre quando backend e interface responderem. Não é necessário editar `.env` ou preencher tokens para abrir o aplicativo. Veja o [contrato do inicializador e a recuperação de falhas](inicializacao-windows.md).

Abra **Configurar**. O [assistente por etapas](assistente-primeira-configuracao.md) permite criar a proteção no servidor, testar e salvar as integrações sem editar credenciais em arquivos. Configurações existentes por ambiente são preservadas. Para usar o catálogo, configure TMDB; busca e downloads podem ser adiados. O guia manual abaixo continua disponível para instalações administradas por ambiente e outros sistemas.

## Configuração manual por ambiente

Esta alternativa administrativa usa três integrações independentes: TMDB para o catálogo, Prowlarr para busca de fontes e qBittorrent para downloads. O assistente é a opção recomendada para preencher credenciais no Windows.

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

No Windows, use `iniciar-qlcstream.bat` para iniciar novamente, inclusive após reiniciar o computador e abrir Docker Desktop. Ele reutiliza imagens, contêineres e volumes. Use `docker compose stop` para parar. Na administração manual, `docker compose up -d` também pode aplicar alterações de configuração e recriar contêineres; confira o [guia do inicializador](inicializacao-windows.md). Cancelar um download no aplicativo mantém os arquivos já salvos.

As integrações do Compose usam qBittorrent `5.3.0-2` e Prowlarr `2.7.0.5723-ls168`, com digests fixados. Consulte a [matriz, conferência da versão executada e promoção explícita](versoes-integracoes.md). O inicializador preserva contêineres existentes; atualizar o checkout não aplica automaticamente essas referências à instalação diária.
