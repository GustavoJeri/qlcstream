# Histórico deste fork

## 2026-11-12 — Confiabilidade dos downloads

Primeiro incremento sobre `diegodinizm1/qlcstream`, commit `27d9065015c47615c25be2d1ae60b82d5c39f4a6`.

### Correções

- Cancelamento de tentativas sem hash, sem remover a transferência pertencente a outro pedido.
- Recuperação e atualização de registros que entraram em erro.
- Localização da transferência por hash, com alternativa por pasta e tolerância à barra final.
- Reutilização de pedidos repetidos e serialização das submissões no banco.
- Recuperação de transferências que chegaram ao motor, mas ficaram sem registro no aplicativo.
- Comandos `stop`, `start` e `recheck` compatíveis com qBittorrent 5.
- Tratamento dos formatos de sucesso legados e dos resumos JSON de inclusão, inclusive operações pendentes HTTP 202.
- Mensagens visíveis de falha no controle e na solicitação de downloads.
- Preservação do torrent quando a descoberta de vídeos pela biblioteca falha temporariamente.
- Reconhecimento de transferências completas em fila de compartilhamento.

### Validação

- 24 testes aprovados: 21 de regressão dos downloads e 3 testes existentes.
- Backend e frontend compilados.
- Validação em uma instalação com qBittorrent 5.3.0 e PostgreSQL: pedidos simultâneos, recuperação de transferência sem registro e inclusão de uma fonte nova criada especificamente para o teste.

### Procedência

As duplicatas sem vínculo, o bloqueio de cancelamento sem hash, a interrupção da sincronização em erro e os comandos antigos já estavam no código de origem. A rejeição dos resumos JSON de sucesso foi introduzida em uma revisão local intermediária e corrigida antes desta publicação. Ela não deve ser atribuída ao projeto original.

A configuração guiada ainda é trabalho futuro, descrito no [plano de evolução](docs/roadmap.md).
