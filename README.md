# QLC Stream

Aplicativo local para catálogo pessoal de filmes, descoberta de opções de download e gerenciamento da biblioteca no computador. O projeto não inclui player ou streaming.

## Sobre este fork

Este fork de [diegodinizm1/qlcstream](https://github.com/diegodinizm1/qlcstream) preserva a autoria e o histórico do projeto original. O foco é tornar os downloads confiáveis e simplificar o primeiro uso por meio de incrementos pequenos e verificáveis.

**Primeiro incremento entregue:** cancelamento de tentativas órfãs, recuperação de erros e de transferências sem registro, prevenção de pedidos repetidos e compatibilidade de inclusão com qBittorrent 5.3. A entrega inclui 21 testes de regressão dos downloads; a suíte completa tem 24 testes.

- [O que mudou e o que foi validado](CHANGELOG.md)
- [Como os downloads se comportam](docs/downloads-confiabilidade.md)
- [Primeira execução e configuração pelo assistente](docs/primeira-execucao.md)
- [Inicialização no Windows e recuperação de falhas](docs/inicializacao-windows.md)
- [Diagnóstico da pasta de mídia e limites da verificação dos volumes](docs/diagnostico-armazenamento.md)
- [Plano de evolução: diagnóstico e configuração guiada](docs/roadmap.md)
- [Diagnóstico das conexões: estados, limites e validação isolada](docs/diagnostico-integracoes.md)

A tela de configurações testa as conexões autenticadas com TMDB, Prowlarr e qBittorrent e diagnostica leitura e escrita na pasta salva. Orienta a recuperação de falhas e mantém o compartilhamento físico dos volumes inconclusivo quando não há prova. O [assistente em Configurar](docs/assistente-primeira-configuracao.md) orienta as integrações por etapas e preserva configurações existentes.

## Estrutura

- `backend`: API Java 21 com Spring Boot e arquitetura de monólito modular.
- `frontend`: aplicação Angular.
- `docs`: decisões e visão de arquitetura.
- `compose.yaml`: PostgreSQL, backend e frontend para desenvolvimento local.

## Primeira execução

No Windows, instale e abra Docker Desktop em modo Linux e execute **`iniciar-qlcstream.bat`**. O inicializador verifica requisitos e portas, preserva configurações existentes, prepara somente imagens ausentes e abre o navegador quando backend e interface respondem. Não é necessário preencher credenciais para abrir o [assistente em Configurar](docs/assistente-primeira-configuracao.md).

Para uso diário, execute o mesmo arquivo; para parar, use `docker compose stop`. Confira o [contrato, diagnóstico e opções administrativas](docs/inicializacao-windows.md). Em outros sistemas ou para administração manual, consulte o [guia completo](docs/primeira-execucao.md).

O PostgreSQL fica disponível apenas para os demais contêineres. O endpoint de saúde do backend pode ser consultado em `http://localhost:8080/actuator/health`.

Com `TMDB_API_TOKEN` configurado, o catálogo pode ser consultado em:

```text
GET http://localhost:8080/api/catalog/trending
GET http://localhost:8080/api/catalog/search?query=Matrix
```

## Desenvolvimento sem Docker

O CI verifica automaticamente os testes do backend e os testes e a compilação do frontend em PRs para `develop` e `main`, e em alterações nessas branches. Veja [CI do projeto](docs/ci.md) para detalhes e execução local.

O backend requer Java 21. O frontend requer uma versão de Node suportada pelo Angular 22, preferencialmente Node 26.

```text
cd backend  && ./mvnw spring-boot:run
cd frontend && npm install && npm start
```

Para executar o backend fora do Docker, defina as variáveis de conexão descritas em `.env.example` e mantenha o PostgreSQL acessível.

As integrações também podem ser configuradas pela API sem recriar o backend. Consulte [precedência, proteção dos segredos e recuperação](docs/configuracao-integracoes.md). O assistente visual está descrito no [guia de configuração](docs/assistente-primeira-configuracao.md).
