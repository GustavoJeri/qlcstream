# QLC Stream

Aplicativo local para descobrir filmes e séries, organizar uma biblioteca pessoal, encontrar opções em indexadores configurados pelo usuário e acompanhar downloads pelo qBittorrent.

> O QLC Stream não fornece conteúdo, não inclui indexadores e não é um serviço de streaming. Use apenas fontes e arquivos que você tem autorização para acessar.

## O que o aplicativo oferece

- catálogo de filmes, séries e pessoas com dados do TMDB;
- busca e ordenação de títulos;
- favoritos, listas personalizadas e itens salvos na biblioteca;
- busca de opções de download pelo Prowlarr;
- envio e acompanhamento de downloads pelo qBittorrent;
- organização dos arquivos concluídos em uma pasta local;
- interface web e aplicativo desktop Tauri;
- abertura de vídeos da biblioteca no VLC pelo aplicativo desktop.

## Componentes

| Componente | Tecnologia | Endereço no ambiente Docker |
| --- | --- | --- |
| Interface web | Angular 22 + Nginx | <http://localhost:4200> |
| API | Java 21 + Spring Boot | <http://localhost:8080> |
| Banco de dados | PostgreSQL 18 | apenas na rede interna |
| Prowlarr | serviço de indexadores | <http://localhost:9696> |
| qBittorrent | gerenciador de downloads | <http://localhost:8081> |

Os serviços administrativos são publicados apenas em `localhost`. Eles não ficam disponíveis para outros computadores da rede por padrão.

## Pré-requisitos

Para a forma recomendada de execução, instale:

- Docker Desktop, Docker Engine com Compose ou equivalente;
- uma conta no [TMDB](https://www.themoviedb.org/) para gerar o token do catálogo;
- acesso a indexadores que você tenha permissão para usar.

O aplicativo desktop também requer Node.js, Rust e as dependências do Tauri para o seu sistema. O VLC é necessário somente para abrir vídeos diretamente pela versão desktop.

## Primeira execução pelo assistente

No Windows, abra o Docker Desktop em modo Linux e execute `iniciar-qlcstream.bat`. O inicializador verifica requisitos e portas, preserva as configurações e abre o navegador quando a aplicação estiver pronta. Consulte o [guia de inicialização no Windows](docs/inicializacao-windows.md).

Em qualquer sistema, a opção **Configurar** guia a configuração de TMDB, Prowlarr, qBittorrent e da pasta de mídia. A tela de configurações permite testar conexão e autenticação, além de verificar o acesso do backend à pasta de mídia. Confira o [guia do assistente](docs/assistente-primeira-configuracao.md) e a [precedência das configurações e proteção dos segredos](docs/configuracao-integracoes.md).

## Instalação manual com Docker

### 1. Crie o arquivo de configuração

Na raiz do projeto, copie o modelo:

```bash
cp .env.example .env
```

Abra `.env` em um editor. Não compartilhe nem envie esse arquivo ao Git: ele contém credenciais e já está listado no `.gitignore`.

### 2. Preencha as variáveis

Um exemplo completo:

```dotenv
POSTGRES_DB=qlc_stream
POSTGRES_USER=qlc_stream
POSTGRES_PASSWORD=troque-por-uma-senha-forte

TMDB_API_TOKEN=cole-aqui-o-token-de-leitura-da-api

PROWLARR_BASE_URL=http://prowlarr:9696
PROWLARR_API_KEY=cole-aqui-a-chave-do-prowlarr

QBITTORRENT_BASE_URL=http://qbittorrent:8080
QBITTORRENT_USERNAME=admin
QBITTORRENT_PASSWORD=cole-aqui-a-senha-do-qbittorrent

MEDIA_HOST_PATH=./data/media
```

Não use aspas ao redor dos valores. No Docker, mantenha os nomes internos `prowlarr:9696` e `qbittorrent:8080`; `localhost` apontaria para o próprio contêiner do backend.

### 3. Inicie os serviços pela primeira vez

```bash
docker compose up -d --build
```

Confira o estado dos contêineres:

```bash
docker compose ps
```

Na primeira inicialização, o catálogo ainda funcionará somente depois de configurar o token do TMDB. A busca e os downloads dependem das configurações adicionais abaixo.

## Como obter e preencher cada configuração

### Banco de dados

| Variável | Como preencher |
| --- | --- |
| `POSTGRES_DB` | Nome do banco. Pode manter `qlc_stream`. |
| `POSTGRES_USER` | Usuário do banco. Pode manter `qlc_stream` em uso local. |
| `POSTGRES_PASSWORD` | Defina uma senha longa e exclusiva. |

Esses três valores são usados pelo PostgreSQL e pelo backend. Depois que o volume do banco for criado, mudar usuário, senha ou nome no `.env` não altera automaticamente as credenciais já gravadas. Consulte [Recriar o ambiente](#recriar-o-ambiente) se precisar começar do zero.

### TMDB

O TMDB fornece capas, sinopses, notas, elenco e demais dados do catálogo.

1. Entre na sua conta do TMDB.
2. Abra as configurações da conta e acesse a área **API**.
3. Solicite acesso, se necessário.
4. Copie o **API Read Access Token** — o token longo usado como `Bearer`, não apenas a chave curta da API v3.
5. Cole o valor em `TMDB_API_TOKEN`.

Após alterar o `.env`, recrie o backend:

```bash
docker compose up -d --force-recreate backend
```

### Prowlarr

O Prowlarr pesquisa os indexadores configurados pelo próprio usuário.

1. Com os contêineres em execução, abra <http://localhost:9696>.
2. Conclua a configuração inicial e proteja a interface com autenticação.
3. Adicione somente os indexadores que você está autorizado a usar e teste cada conexão.
4. Em **Settings > General > Security**, copie a chave de API.
5. Cole a chave em `PROWLARR_API_KEY`.
6. Mantenha `PROWLARR_BASE_URL=http://prowlarr:9696`.
7. Recrie o backend para carregar o novo valor.

```bash
docker compose up -d --force-recreate backend
```

Não é necessário expor a chave no frontend. O navegador conversa apenas com o backend.

### qBittorrent

O qBittorrent recebe os downloads escolhidos na interface e informa o progresso.

1. Abra <http://localhost:8081>.
2. Se a instalação exibir uma senha temporária, consulte os registros do serviço:

   ```bash
   docker compose logs qbittorrent
   ```

3. Entre na interface e defina uma senha permanente nas configurações da Web UI.
4. Preencha `QBITTORRENT_USERNAME` e `QBITTORRENT_PASSWORD` com as mesmas credenciais.
5. Mantenha `QBITTORRENT_BASE_URL=http://qbittorrent:8080`. A porta `8081` é usada somente pelo navegador no computador anfitrião.
6. Recrie o backend:

   ```bash
   docker compose up -d --force-recreate backend
   ```

Se o Docker Desktop bloquear a comunicação por validação de host, pare o serviço e ajuste `WebUI\\HostHeaderValidation=false` na seção `[Preferences]` de `data/qbittorrent-official/qBittorrent/config/qBittorrent.conf`. Depois, inicie-o novamente. O contêiner deve estar parado durante a edição para não regravar o arquivo.

### Pasta da biblioteca

`MEDIA_HOST_PATH` é a pasta do computador compartilhada entre backend e qBittorrent.

- `./data/media` cria a biblioteca dentro do projeto;
- um caminho absoluto, como `/Users/voce/Filmes`, usa uma pasta externa;
- a pasta precisa estar liberada para compartilhamento no Docker Desktop;
- backend e qBittorrent precisam continuar apontando para a mesma pasta.

Na interface, **Configurações > Pasta dos downloads** define uma subpasta dentro dessa biblioteca, por exemplo `filmes`, `series` ou `filmes/4k`. Não informe um caminho absoluto nesse campo.

## Referência das variáveis

| Variável | Obrigatória | Valor no Docker | Finalidade |
| --- | --- | --- | --- |
| `POSTGRES_DB` | sim | `qlc_stream` | nome do banco |
| `POSTGRES_USER` | sim | `qlc_stream` | usuário do banco |
| `POSTGRES_PASSWORD` | sim | valor exclusivo | senha do banco |
| `TMDB_API_TOKEN` | para o catálogo | token de leitura | autenticação no TMDB |
| `PROWLARR_BASE_URL` | para buscas | `http://prowlarr:9696` | endereço interno do Prowlarr |
| `PROWLARR_API_KEY` | para buscas | chave do Prowlarr | autenticação no Prowlarr |
| `QBITTORRENT_BASE_URL` | para downloads | `http://qbittorrent:8080` | endereço interno do qBittorrent |
| `QBITTORRENT_USERNAME` | para downloads | usuário da Web UI | login no qBittorrent |
| `QBITTORRENT_PASSWORD` | para downloads | senha da Web UI | login no qBittorrent |
| `MEDIA_HOST_PATH` | sim | `./data/media` | pasta física da biblioteca |
| `TMDB_CACHE_MAX_ENTRIES` | não | `300` | máximo de itens no cache em memória |
| `TMDB_CACHE_CATALOG_TTL` | não | `10m` | validade do catálogo em cache |
| `TMDB_CACHE_SEARCH_TTL` | não | `3m` | validade das buscas em cache |

As variáveis de cache são opcionais e usam os valores mostrados quando omitidas.

## Como usar o aplicativo

### Confirmar se está pronto

1. Abra <http://localhost:4200>.
2. Acesse **Configurações**.
3. Confirme que Catálogo, Opções de download e Downloads aparecem como disponíveis.
4. Escolha e salve a subpasta dos próximos downloads.

Use os testes da tela para verificar conectividade, autenticação e acesso à pasta. Para verificar o fluxo completo, faça também uma consulta no catálogo, uma busca de opção e um download de teste autorizado.

### Explorar filmes e séries

1. Use **Filmes** ou **Séries** no menu.
2. Navegue pelas tendências ou pesquise um título.
3. Abra o título para consultar sinopse, elenco, temporadas e episódios.
4. Use o coração para favoritar ou adicione o título à biblioteca.
5. Organize favoritos e títulos em coleções na tela **Biblioteca**.

### Iniciar e acompanhar um download

1. Abra os detalhes de um filme ou de uma série.
2. Para séries, selecione a temporada e, quando aplicável, o episódio.
3. Clique para procurar opções disponíveis.
4. Confira título, fonte, tamanho e quantidade de pessoas compartilhando.
5. Escolha **Baixar** somente em uma fonte que você possa usar legalmente.
6. Abra **Downloads** para acompanhar progresso, velocidade e tempo estimado, pausar, continuar ou tentar novamente.

Quando o download termina, o backend registra arquivos de vídeo com extensões compatíveis (`mkv`, `mp4`, `avi`, `mov`, `m4v` e `webm`) na biblioteca.

### Usar a versão desktop

A versão desktop é uma interface adicional; os serviços Docker e o backend precisam continuar em execução.

```bash
docker compose up -d database backend prowlarr qbittorrent
cd frontend
npm ci
npm run desktop:dev
```

Ela permite selecionar pastas dentro da biblioteca, revelar arquivos no gerenciador do sistema e abrir vídeos no VLC. Para gerar um instalador:

```bash
cd frontend
npm ci
npm run desktop:build
```

## Desenvolvimento

### Frontend no navegador

Deixe banco, backend e integrações no Docker e execute o Angular em outra porta:

```bash
docker compose up -d database backend prowlarr qbittorrent
cd frontend
npm ci
npm start -- --port 4201
```

Acesse <http://localhost:4201>. A configuração de CORS do backend permite essa origem.

### Backend fora do Docker

Requisitos: Java 21 e um PostgreSQL acessível pelo computador. O arquivo `.env` não é carregado automaticamente pelo Spring; exporte as variáveis no terminal ou configure-as na IDE.

Além das variáveis da tabela, use endereços alcançáveis pelo computador:

```bash
export DB_URL='jdbc:postgresql://localhost:5432/qlc_stream'
export DB_USER='qlc_stream'
export DB_PASSWORD='sua-senha'
export PROWLARR_BASE_URL='http://localhost:9696'
export QBITTORRENT_BASE_URL='http://localhost:8081'
export MEDIA_ROOT='../data/media'

cd backend
./mvnw spring-boot:run
```

Também exporte `TMDB_API_TOKEN`, `PROWLARR_API_KEY`, `QBITTORRENT_USERNAME` e `QBITTORRENT_PASSWORD`. O PostgreSQL do `compose.yaml` não publica a porta `5432`; para esse modo, use um PostgreSQL local ou publique a porta apenas no seu ambiente de desenvolvimento.

### Testes e build

```bash
cd backend
./mvnw test

cd ../frontend
npm ci
npm test
npm run build
```

## Operação e atualização

Ver registros de todos os serviços:

```bash
docker compose logs -f
```

Ver apenas o backend:

```bash
docker compose logs -f backend
```

Parar sem apagar os dados:

```bash
docker compose down
```

Atualizar imagens e reconstruir o projeto com as [versões fixadas no Compose](docs/versoes-integracoes.md):

```bash
docker compose pull
docker compose up -d --build
```

O banco é atualizado automaticamente pelas migrações do Flyway quando o backend inicia.

### Recriar o ambiente

Para remover contêineres e o volume do PostgreSQL:

```bash
docker compose down --volumes
```

Esse comando apaga os dados do banco, incluindo biblioteca, favoritos, coleções e histórico. Os diretórios em `data/` e a pasta definida em `MEDIA_HOST_PATH` não são removidos por esse comando, mas faça backup antes de qualquer limpeza.

## Verificação e diagnóstico

API e saúde do backend:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/system/integrations
```

Problemas comuns:

| Sintoma | O que verificar |
| --- | --- |
| Catálogo indisponível | se `TMDB_API_TOKEN` é o token de leitura e se o backend foi recriado |
| Nenhuma opção encontrada | indexadores habilitados/testados no Prowlarr e `PROWLARR_API_KEY` correta |
| Falha ao iniciar download | credenciais da Web UI, URL interna e registros do qBittorrent/backend |
| Arquivo não aparece na biblioteca | se ambos os contêineres montam a mesma `MEDIA_HOST_PATH` e se a extensão é suportada |
| Pasta externa não funciona | permissões da pasta e compartilhamento de arquivos do Docker Desktop |
| Mudança no `.env` não teve efeito | recrie o serviço com `docker compose up -d --force-recreate backend` |
| Porta já está em uso | encerre o processo conflitante ou ajuste a porta publicada no `compose.yaml` |

## Estrutura do repositório

```text
.
├── backend/          # API Spring Boot e migrações do banco
├── frontend/         # interface Angular e aplicativo Tauri
├── docs/             # documentação de arquitetura
├── data/             # dados locais ignorados pelo Git
├── compose.yaml      # ambiente completo em contêineres
└── .env.example      # modelo das variáveis de ambiente
```

- [Arquitetura e módulos do backend](docs/architecture.md)
- [Changelog](CHANGELOG.md)
- [Recuperação de downloads](docs/downloads-confiabilidade.md)
- [Diagnóstico de conexões](docs/diagnostico-integracoes.md)
- [Diagnóstico de armazenamento](docs/diagnostico-armazenamento.md)
- [Testes em CI](docs/ci.md)
- [Roadmap](docs/roadmap.md)
