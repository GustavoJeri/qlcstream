# QLC Stream

Aplicativo local para catálogo pessoal de filmes, descoberta de opções de download e gerenciamento da biblioteca no computador. O projeto não inclui player ou streaming.

## Sobre este fork

Este fork de [diegodinizm1/qlcstream](https://github.com/diegodinizm1/qlcstream) preserva a autoria e o histórico do projeto original. O foco é tornar os downloads confiáveis e simplificar o primeiro uso por meio de incrementos pequenos e verificáveis.

**Primeiro incremento entregue:** cancelamento de tentativas órfãs, recuperação de erros e de transferências sem registro, prevenção de pedidos repetidos e compatibilidade de inclusão com qBittorrent 5.3. A entrega inclui 21 testes de regressão dos downloads; a suíte completa tem 24 testes.

- [O que mudou e o que foi validado](CHANGELOG.md)
- [Como os downloads se comportam](docs/downloads-confiabilidade.md)
- [Primeira execução: configuração manual atual](docs/primeira-execucao.md)
- [Plano de evolução: diagnóstico e configuração guiada](docs/roadmap.md)

A configuração guiada ainda não foi implementada. O próximo incremento será testar as conexões de verdade e explicar na interface o que precisa ser corrigido.

## Estrutura

- `backend`: API Java 21 com Spring Boot e arquitetura de monólito modular.
- `frontend`: aplicação Angular.
- `docs`: decisões e visão de arquitetura.
- `compose.yaml`: PostgreSQL, backend e frontend para desenvolvimento local.

## Primeira execução

Para configurar catálogo, busca de fontes e motor de downloads, seguir o [guia completo da primeira execução](docs/primeira-execucao.md). As etapas abaixo resumem a inicialização do ambiente:

1. Copie `.env.example` para `.env`.
2. Preencha `TMDB_API_TOKEN` quando iniciar a integração com o catálogo.
3. Execute `docker compose up --build`.
4. Acesse `http://localhost:4200`.

O PostgreSQL fica disponível apenas para os demais contêineres. O endpoint de saúde do backend pode ser consultado em `http://localhost:8080/actuator/health`.

Com `TMDB_API_TOKEN` configurado, o catálogo pode ser consultado em:

```text
GET http://localhost:8080/api/catalog/trending
GET http://localhost:8080/api/catalog/search?query=Matrix
```

## Desenvolvimento sem Docker

O backend requer Java 21. O frontend requer uma versão de Node suportada pelo Angular 22, preferencialmente Node 26.

```text
cd backend  && ./mvnw spring-boot:run
cd frontend && npm install && npm start
```

Para executar o backend fora do Docker, defina as variáveis de conexão descritas em `.env.example` e mantenha o PostgreSQL acessível.
