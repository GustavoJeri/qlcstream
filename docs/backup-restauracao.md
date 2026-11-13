# Cópia de segurança e restauração — investigação da Issue #21

Iniciada em 13/11/2026 sobre `develop` em `aa18d2b`, após integrar a PR #35 (Issue #20). Este documento registra o inventário e o escopo inicial. **Ainda não oferece um procedimento certificado de backup/restauração.** Nenhum dado ou serviço da instalação diária foi consultado, copiado ou alterado nesta investigação.

## Estado que precisa ser recuperável

Inventário obtido do código versionado e do `compose.yaml`, não da instalação pessoal:

| Componente | Origem declarada | Requisito de recuperação |
| --- | --- | --- |
| PostgreSQL | Volume lógico `postgres_data`, montado em `/var/lib/postgresql` | Catálogo, biblioteca, arquivos registrados, downloads/eventos, preferências de pasta, coleções/favoritos e configurações gerenciadas. Identificar o volume pelo projeto e montagem; não assumir um nome físico de outra instalação. |
| Configurações gerenciadas | Tabela `integration_settings` | Conservar os registros cifrados e sua chave correspondente. A adoção explícita e a precedência sobre o ambiente devem sobreviver. |
| Chave de proteção | `data/integration-secrets/integration-settings.key`, no Compose padrão | Arquivo separado do banco, com acesso protegido. A implementação requer 32 bytes em Base64 e recusa banco gerenciado sem a chave correta. Não gerar outra chave para restaurar dados existentes. |
| Prowlarr | `data/prowlarr` → `/config` | Preservar configuração, banco de indexadores e credenciais do serviço; copiar somente `config.xml` não basta. |
| qBittorrent | `data/qbittorrent-official` → `/config` | Preservar o perfil completo, incluindo configurações de acesso e metadados de torrents. Perfil restaurado não implica que os arquivos transferidos estejam disponíveis. |
| Configuração administrativa | `.env` quando existente, Compose e opções efetivamente usadas | Guardar de forma protegida, sem imprimir seu conteúdo ou publicar `docker compose config` resolvido. A investigação não lê o `.env` pessoal. |
| Identidade da aplicação | Commit, referências/digests e versões executadas | Relacionar a cópia ao código, esquema e imagens correspondentes; imagens de backend/frontend são construídas localmente e também precisam de identificação. |
| Mídia | `MEDIA_HOST_PATH`, compartilhada como `/data` e `/downloads` | Backup separado e opcional. Registros do banco não contêm os bytes dos vídeos, legendas ou downloads incompletos. |

O banco e os perfis podem conter segredos, endereços privados e referências de aquisição. Não devem entrar no Git, em logs compartilhados ou em artefatos públicos do CI. Uma cópia com a chave deve receber proteção equivalente à dos segredos que ela permite recuperar. Manifestos públicos de teste devem conter somente metadados e hashes sintéticos permitidos, sem os arquivos protegidos.

## Contratos já existentes a preservar

- `IntegrationSettings` recusa carregar configurações gerenciadas sem a chave correspondente; o assistente e o inicializador não devem gerar uma substituta durante a recuperação.
- Flyway aplica migrações na inicialização; Hibernate valida o esquema. A existência de uma imagem antiga não demonstra compatibilidade com um banco já migrado.
- A pasta de destino relativa fica em `storage_root`; `local_file` contém referências aos arquivos. Restaurar só o banco pode reabrir registros sem recuperar a mídia.
- Permissões e donos precisam ser conservados. A pasta `incoming` nova usa UID 100, grupo 1000 e modo 2770; o backend recebe grupo suplementar 1000. Diretórios e links existentes não são corrigidos silenciosamente.
- A matriz da Issue #20 já provou cópia offline na mesma versão e mostrou que validar restauração não pode recriar silenciosamente registros ausentes. Isso ainda não certifica um arquivo de backup transportável ou recuperação entre releases.

## Primeiro incremento a implementar e testar

1. Definir manifesto versionado com data UTC, identidade do código/imagens, esquema Flyway, componentes presentes, hashes e exclusões explícitas de mídia. Não incluir valores de segredos no manifesto de diagnóstico.
2. Comparar dump lógico do PostgreSQL com cópia física offline, escolhendo o formato após um ensaio sintético. A consistência interna do banco não substitui a consistência conjunta de banco, chave e perfis; interromper escritores de forma explícita no procedimento que vier a ser validado.
3. Guardar o conjunto coerente de componentes em destino protegido novo. Identificar a chave separadamente e preservar a associação com o banco. Não substituir arquivos anteriores ou operar sobre serviços ativos por padrão.
4. Restaurar primeiro somente em projeto isolado, volumes novos, portas próprias e credenciais fictícias. Recusar destino ocupado, backup incompleto/corrompido, chave ausente/incorreta e versão/esquema incompatível antes de ativar o backend restaurado.
5. Comparar registros essenciais, chave, configuração efetiva, identidade/configuração dos indexadores e bytes de mídia sintética quando incluída. Consultar o aplicativo restaurado sem autorreparar o estado usado como prova.
6. Registrar limites, mensagens recuperáveis e roteiro reproduzível; só então oferecer comandos administrativos utilizáveis. A Issue #21 permanece aberta até cumprir esses testes e passar por PR própria.

Não há autorização automática de restauração sobre a instalação ativa. O procedimento de uso diário e a publicação em `main` continuam separados. Downgrade de PostgreSQL, reversão Flyway e recuperação de dados depois de migrações não serão prometidos sem ensaio específico.

## Referências iniciais

- [Configuração gerenciada e proteção da chave](configuracao-integracoes.md).
- [Imagens fixadas, matriz e limites de recuperação](versoes-integracoes.md).
- [PostgreSQL 18: dump lógico](https://www.postgresql.org/docs/18/backup-dump.html) e [cópia física](https://www.postgresql.org/docs/18/backup-file.html), consultados em 13/11/2026. A cópia física comum requer parar o servidor; a comparação de formatos ainda está pendente para o produto.
- [Docker: backup e restauração de volumes](https://docs.docker.com/engine/storage/volumes/#back-up-restore-or-migrate-data-volumes), consultado em 13/11/2026. Exemplos de cópia de volume não certificam consistência dos serviços que escrevem nele.
