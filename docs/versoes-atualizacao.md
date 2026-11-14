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

- Definir a origem de versão e o inventário permitido do pacote, incluindo o tratamento do scaffold Tauri.
- Definir como o manifesto de distribuição substitui a dependência de `.git` sem enfraquecer a identificação/compatibilidade do backup.
- Escolher e ensaiar a base de atualização suportada e a candidata.
- Implementar empacotamento, identificação em execução, verificações de integridade e roteiro de recuperação.
- Preparar e revisar uma PR própria da implementação. A Issue #22 permanece aberta; publicação de release, promoção de `main` e atualização diária continuam pendentes.

Referências de contexto já disponíveis: [Issue #22](https://github.com/GustavoJeri/qlcstream/issues/22), [plano #26](https://github.com/GustavoJeri/qlcstream/issues/26), [matriz das integrações](versoes-integracoes.md), [backup/restauração](backup-restauracao.md) e [inicializador Windows](inicializacao-windows.md).
