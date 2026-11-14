# Cópia de segurança e restauração — Issue #21

Iniciada em 13/11/2026 sobre `develop` em `aa18d2b`, após integrar a PR #35 (Issue #20). O inventário original está preservado abaixo. O procedimento implementado e seus limites estão nas seções seguintes. Nenhum dado ou serviço da instalação diária foi consultado, copiado ou alterado nos ensaios.

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

## Escopo registrado no inventário inicial

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


## Contrato implementado: formato 1

`scripts/qlc-backup.py` usa Python 3.12 ou superior no host e Docker Engine Linux. A ferramenta auxiliar é construída explicitamente; nenhum requisito é instalado automaticamente no host. Os comandos não abrem `.env`, não executam o Compose da instalação diária, não param serviços e não iniciam downloads.

O pacote principal fica em um **volume Docker novo**, sob `/vault/package`, com diretório 0700 e arquivos 0600. Contém `database.dump` no formato custom do `pg_dump`, `prowlarr.tar`, `qbittorrent.tar`, `media.tar`, `manifest.json` e o marcador final `COMPLETE`. A chave original fica em **outro volume**, sob `/keyvault/key/integration-settings.key`, com pasta 0700 e arquivo 0600. Os nomes começam com `qlcbackup-`. A associação é verificada pelo hash da chave e pela autenticação AES-GCM de cada registro gerenciado, antes de escrever no destino de restauração.

O manifesto privado identifica formato, UTC, commit do checkout, hashes dos arquivos de execução e migrações, identidade efetiva das imagens (ID, referência, digests, SO e arquitetura), histórico Flyway, snapshot de todas as tabelas públicas, configuração efetiva dos contêineres, componentes, hashes SHA-256 e exclusões. Ele contém dados sensíveis e recebe a mesma proteção do dump. Os helpers usam `--log-driver none`: manifesto e dump são recebidos somente pelo processo chamador, sem passar pelo driver de logging do Docker. Capturar stdout sozinho não oferece essa proteção. Somente mensagens fixas de sucesso/erro são exibidas. Valores de ambiente são passados ao Docker por ambiente do processo, sem senhas nos argumentos ou em arquivos temporários.

**Proteção por controle de acesso, sem cifragem do pacote em repouso.** Administradores do host e pessoas com acesso ao Docker podem ler volumes, contêineres e segredos. A chave fica separada do pacote do banco, conforme o contrato das integrações. Ambos precisam de guarda protegida. O volume local protege contra uma alteração da aplicação; sozinho, não protege contra perda do disco ou do Docker Desktop. Para guarda externa, um administrador deve usar armazenamento cifrado com controle de acesso, preservar os dois volumes separadamente e ensaiar a importação antes de confiar nessa cópia. Exportação, importação, nuvem e transporte não são automatizados nem comprovados por este roteiro. Não publique volumes, arquivos de pacote, chave, dumps ou perfis em Git, chamados, logs ou artefatos de CI.

### Escolha de formato e consistência

O PostgreSQL permanece ativo, enquanto frontend, backend, Prowlarr e qBittorrent devem estar **parados normalmente**. O comando recusa escritores ativos, perfis compartilhados com contêineres ativos, encerramento forçado, montagens adicionais, `volume-subpath` e réplicas. A guarda consulta somente os metadados de montagem dos contêineres ativos, compara a origem dos binds e detecta sobreposição de diretórios; usar o mesmo destino `/config` com outra origem não bloqueia a operação. Não lê ambiente, logs ou conteúdo de outras instalações. Aliases físicos não representados nesses metadados exigem revisão administrativa; não se promete resolver equivalência física arbitrária. O backend pode retornar 143 após SIGTERM; 137 não é aceito. Sessões de cliente adicionais no banco são recusadas. Durante toda a janela, suspenda também tarefas administrativas, clientes externos e mudanças de configuração/montagens. A ferramenta não bloqueia um administrador que inicie outro escritor depois da conferência; exclusividade da manutenção é uma pré-condição.

`pg_dump -Fc --no-owner --no-acl` fornece um snapshot lógico transacional. Todas as tabelas públicas são comparadas antes/depois para detectar alterações durante a coleta. Os serviços de integração parados permitem copiar seus perfis completos, incluindo SQLite e metadados de torrents, preservando dono, grupo e modos Linux. A chave é validada contra os registros desse snapshot. Links, dispositivos e tipos especiais são recusados, inclusive na origem. Os arquivos TAR passam por validação integral de caminhos, duplicatas e tipos antes da extração.

Escolhemos dump lógico para não copiar arquivos internos do PostgreSQL ou depender de uma cópia física do cluster. Uma cópia física comum exigiria parar todo o servidor e incluir o cluster completo, WAL e demais diretórios associados; copiar alguns arquivos do banco não recupera tabelas. Os TAR dos perfis são simples cópias de arquivos offline, e não dumps lógicos desses serviços. Nenhum dos formatos permite copiar um SQLite ativo com segurança por mera suposição.

### Incluído e excluído

Inclui banco da aplicação e histórico Flyway, registros de mídia e downloads, biblioteca/coleções/favoritos, preferências de pasta, configurações gerenciadas cifradas, chave correspondente separada, perfis completos de Prowlarr e qBittorrent, ambiente efetivamente carregado pelos cinco serviços e sinal/prazo de parada de cada contêiner. O qBittorrent conserva os 1800 segundos definidos pelo Compose. A topologia padrão exige que o backend use o serviço `database`, as mesmas credenciais administrativas declaradas e `MEDIA_ROOT=/data`; PGDATA deve permanecer sob a montagem PostgreSQL padrão. A configuração de reconstrução vem dos contêineres identificados pelo projeto; não é uma cópia do `.env` do host. O banco novo usa as credenciais administrativas efetivas guardadas no manifesto.

O dump e o snapshot de linhas passam pela memória do host e da ferramenta; é necessário espaço em disco para o pacote e memória proporcional ao banco. Esta implementação não oferece streaming de bancos grandes.

Sem `--include-media`, `media.tar` contém **somente diretórios e permissões**, sem bytes de mídia, legendas, arquivos incompletos ou marcadores de teste. Com essa opção, inclui todos os arquivos regulares sob a montagem compartilhada, enquanto os motores estão parados; pode exigir bastante espaço e tempo. Não há seleção parcial, sincronização ou deduplicação. Registros restaurados podem apontar para arquivos ausentes e não significam recuperação dos vídeos.

Exclui outras bases, papéis/ACL/tablespaces do cluster, serviços externos e suas contas, camadas das imagens, código-fonte do checkout, arquivos administrativos do host, `.env`, logs do Docker e arquivos fora das montagens declaradas. Guarde separadamente o checkout correspondente e as imagens identificadas pelo manifesto; não há download ou build automático na restauração. Configurações avançadas com montagens extras, links, subdiretórios de volumes (`volume-subpath`), outros usuários proprietários de objetos SQL, múltiplos bancos ou múltiplas réplicas exigem outro procedimento e são fora do contrato.

## Executar backup

Antes da manutenção, confirme o nome do projeto e os serviços que serão afetados. Os comandos seguintes são **administrativos**, não devem ser usados para os ensaios da fixture contra a instalação diária.

Construa explicitamente a ferramenta, na raiz do checkout:

```powershell
docker build -t qlcstream-backup-helper:1 scripts/backup
```

Pare os escritores pelo Compose que pertence à instalação escolhida, mantendo apenas o banco ativo. Por exemplo, no Compose padrão:

```powershell
docker compose stop --timeout 1800 frontend backend prowlarr qbittorrent
python scripts/qlc-backup.py backup --project qlc-stream --vault qlcbackup-20261113-config --key-vault qlcbackup-20261113-chave
python scripts/qlc-backup.py verify --vault qlcbackup-20261113-config --key-vault qlcbackup-20261113-chave
```

O exemplo concede até 30 minutos para o encerramento, respeitando a janela já prevista para o qBittorrent. Aguarde a parada normal; um encerramento forçado será recusado pelo backup. A fixture pequena usa 60 segundos apenas para seus serviços sintéticos.

Use nomes novos em cada execução. Para incluir mídia, acrescente `--include-media` ao comando `backup`. A origem fica no mesmo estado: banco ativo, demais serviços parados; o operador pode retomá-los explicitamente com `docker compose start frontend backend prowlarr qbittorrent`. O backup não troca chave nem regrava os perfis. Instalações somente por ambiente devem provisionar conscientemente uma chave antes deste procedimento; o formato 1 exige chave original presente, mesmo sem registros gerenciados.

`BACKUP_COMPLETO` indica publicação do marcador depois da coleta e conferência. `verify` confere manifesto, compatibilidade, hashes, segurança dos TAR e a associação da chave. Não equivale a abrir a aplicação restaurada. Uma falha preserva os arquivos parciais privados, sem anunciá-los como válidos; não use novamente aqueles nomes. Um contêiner parado com sufixo `-claim` reserva a operação e impede duas execuções da ferramenta no mesmo destino.

## Restaurar em instalação nova e isolada

Use o checkout compatível e mantenha disponíveis as imagens originais no Docker local. Não execute o inicializador diário nem `setup-directories` sobre a restauração. Exemplo:

```powershell
python scripts/qlc-backup.py restore --vault qlcbackup-20261113-config --key-vault qlcbackup-20261113-chave --target qlcbackup-recuperacao-20261113
```

Antes de qualquer volume de destino, o comando confere o pacote, a chave, os hashes de código/migrações e a disponibilidade das imagens. Recusa nomes ocupados de volumes, rede e contêineres; não limpa nem sobrescreve. Cria cinco volumes novos, rede interna sem portas públicas e cinco contêineres, reaplicando `StopTimeout`/`StopSignal` da origem. Importa o dump com `pg_restore --single-transaction --exit-on-error`, compara **todas as linhas de todas as tabelas públicas** com o snapshot de origem e deixa os cinco serviços parados. Nenhum backend é iniciado antes dessa comparação. O PostgreSQL de destino é iniciado somente para importar/verificar e depois parado.

`RESTAURACAO_VERIFICADA` comprova essa comparação e a extração do conjunto validado. Ainda é necessário reabrir o aplicativo e conferir os serviços. Antes de iniciar, revise no ambiente privado os endereços efetivos: URLs ou credenciais para serviços fora da rede restaurada podem continuar apontando para contas reais. Uma rede Docker interna reduz acesso externo, mas não torna seguros todos os endereços do host. Não ative motores com perfis reais durante um teste. Na fixture os endereços são exclusivamente os serviços controlados.

Os nomes são `<target>-database`, `-backend`, `-frontend`, `-prowlarr` e `-qbittorrent`. Após a revisão, inicie primeiro o banco e depois os outros serviços com `docker start <nome>`. Não há portas publicadas; para inspeção, use um cliente dentro da rede interna. A publicação local de portas e adoção da instalação recuperada são decisões administrativas posteriores, fora deste comando. Não execute o Compose principal supondo que ele adotará esses volumes: são destinos distintos.

## Recusas e falhas recuperáveis

| Código | Ação |
| --- | --- |
| `PARE_ESCRITORES_NORMALMENTE`, `ESCRITORES_ATIVOS`, `VOLUME_EM_USO` | Confira somente a instalação escolhida e encerre seus escritores normalmente; não mate processos. |
| `CONEXOES_EXTERNAS_ATIVAS`, `BANCO_ALTERADO_DURANTE_BACKUP` | Suspenda clientes e tarefas externas durante toda a manutenção. |
| `BACKUP_INCOMPLETO`, `BACKUP_CORROMPIDO`, `PACOTE_INVALIDO` | Preserve para análise privada e obtenha outra cópia íntegra; não crie manualmente `COMPLETE`. |
| `CHAVE_AUSENTE`, `CHAVE_INCORRETA`, `CHAVE_INVALIDA` | Forneça o volume com a chave original associada ao banco; não gere substituta. |
| `VERSAO_ESQUEMA_INCOMPATIVEL`, `IMAGEM_ORIGINAL_AUSENTE` | Use arquivos de execução/migrações e imagens originais; não force a inicialização com outra versão. |
| `DESTINO_OCUPADO` | Escolha outro nome novo; dados anteriores e destinos parciais permanecem preservados. |
| `LINK_OU_TIPO_NAO_SUPORTADO`, `ARQUIVO_INSEGURO`, `MONTAGEM_EXTRA_NAO_SUPORTADA`, `TOPOLOGIA_NAO_SUPORTADA`, `SUBDIRETORIO_DE_VOLUME_NAO_SUPORTADO` | Revise o layout e escolha procedimento compatível; não siga links ou corrija permissões silenciosamente. |
| `COMANDO_FALHOU`, `OPERACAO_FALHOU`, `BANCO_NAO_PRONTO`, `REGISTROS_RESTAURADOS_DIVERGENTES` | Corrija a causa localmente, preserve origem/pacote, examine o destino privado e repita em outro destino novo. Não considere a recuperação concluída. |

Os volumes e a reserva parcial são mantidos depois de uma falha, sem limpeza automática. A fonte nunca é substituída pela cópia. A ferramenta não oferece substituição da instalação ativa, remoção de volumes ou limpeza global.

## Compatibilidade, Flyway e limites

Formato 1 aceita somente os mesmos arquivos de execução versionados de backend/frontend e migrações, os mesmos IDs de imagem e a mesma plataforma das imagens disponíveis. O commit é registrado, mas commits só de documentação ou testes Java excluídos da identidade podem compartilhar o contrato. Pacotes criados antes da correção da revisão da PR #36, sem os campos de sinal/prazo de parada, são recusados como `BACKUP_INCOMPLETO` antes de criar qualquer destino. A ausência desses dados não é substituída por uma suposição; gere um novo backup consistente. O histórico Flyway precisa corresponder aos scripts locais, com sucesso em todas as migrações e checksums CRC32 compatíveis. Na restauração, o histórico e os registros são comparados antes de iniciar o backend; ao iniciar depois, Flyway mantém sua validação normal e Hibernate valida o esquema.

Não há `flyway repair`, `baseline`, `undo`, remoção de histórico ou rollback de esquema. Um dump antigo permite reconstruir **outro banco no estado daquele backup**, com o código correspondente; isso não desfaz migrações sobre a instalação ativa nem recupera alterações posteriores ao backup. Atualizar para outra versão exige uma cópia independente e ensaio próprio de migração. Downgrade do PostgreSQL, mudanças das bases de Prowlarr/qBittorrent e recuperação entre releases não foram demonstrados. A associação entre chave e banco deve permanecer intacta.

O comando não garante recuperação de mídia excluída, dados posteriores à coleta, corrupção prévia já presente na origem, serviços remotos ou perda simultânea dos volumes locais. Hashes detectam corrupção acidental, não autenticam um pacote contra substituição deliberada do manifesto por alguém que controla o Docker. Restaure somente pacotes confiáveis: dumps SQL e perfis podem conter instruções ou configurações perigosas.

## Validação reproduzível

```powershell
docker build -t qlcstream-backup-helper:1 scripts/backup
docker run --rm --network none -v "${PWD}/docs/validation:/tests:ro" --entrypoint python3 qlcstream-backup-helper:1 /tests/test-backup-package.py
python docs/validation/test-backup-command.py
python docs/validation/test-backup-restore.py
powershell.exe -NoProfile -ExecutionPolicy Bypass -File docs/validation/Test-WindowsLauncher.ps1
pwsh -NoProfile -File docs/validation/Test-WindowsLauncher.ps1
```

O ensaio cria projeto aleatório, portas não publicadas, rede interna, volumes novos e um bind sintético próprio para Prowlarr, chave e credenciais fictícias, indexador Torznab próprio e metadados de torrent privado sem trackers. Não transfere conteúdo externo. Preparação da instalação limpa e comparação da restauração são funções distintas. O validador não cria chave, indexador, torrent ou registros ausentes para fazer a cópia passar. Pacotes, chave, dumps, perfis e snapshots ficam privados nos volumes; somente o resultado sintético sem configuração vai para `data/backup-validation-*/result.json`, ignorado pelo Git. O ensaio inclui escritor externo controlado no mesmo bind, escritor não relacionado em outra origem com o mesmo destino, recusa de subpaths iguais/diferentes e inspeção de logging dos canais reais de manifesto/dump. Compara os sinais e prazos de parada antes de iniciar a cópia recuperada. O job `Backup restore` não publica artefatos. O roteiro encerra somente seus próprios contêineres; mantém volumes sintéticos para revisão.

## Fontes oficiais consultadas em 13/11/2026

- [PostgreSQL 18: pg_dump](https://www.postgresql.org/docs/18/app-pgdump.html): snapshot consistente, formato custom, escopo de uma base e exclusão dos objetos globais.
- [PostgreSQL 18: backup físico](https://www.postgresql.org/docs/18/backup-file.html): parada e cópia do cluster completo; não confundir com dump lógico.
- [Docker: volumes e backup/restauração](https://docs.docker.com/engine/storage/volumes/): armazenamento gerenciado, montagens e cópia de volumes.
- [LinuxServer: Prowlarr](https://docs.linuxserver.io/images/docker-prowlarr/): persistência de `/config` e UID/GID. A página FAQ Servarr foi consultada, mas não retornou conteúdo legível; não foi usada como prova.
- [qBittorrent: imagem oficial](https://github.com/qbittorrent/docker-qbittorrent-nox) e [FAQ do projeto](https://github.com/qbittorrent/qBittorrent/wiki/Frequently-Asked-Questions): perfil persistente, configurações e dados de sessão separados dos arquivos baixados.
- [Flyway: schema history](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/flyway-schema-history-table): versões, checksums e estado de sucesso; restaurar histórico não implementa migração inversa.


## Evidências locais — 13/11/2026

- Docker Desktop/Engine 29.8.0, imagens fixadas da matriz e backend/frontend construídos a partir deste checkout: ensaio completo aprovado na execução `8f66dcecca`. Resultado sintético local em `data/backup-validation-8f66dcecca/result.json`, ignorado pelo Git; nenhum pacote, perfil ou chave foi publicado.
- Duas cópias consistentes, uma sem bytes de mídia e outra com mídia própria: origem conferida antes/depois e reaberta ao final. Restaurações em volumes novos compararam todas as linhas das tabelas públicas, histórico Flyway, configuração gerenciada efetiva, indexador original e metadados do torrent. `owned.txt` foi comparado byte a byte quando incluído e comprovadamente ausente na cópia sem mídia, mantendo o registro da biblioteca. `incoming` preservou UID 100, GID 1000 e modo 2770; a extração compara bytes e metadados numéricos antes de iniciar os serviços.
- Recusados chave omitida, arquivo da chave ausente, chave incorreta, marcador de conclusão ausente, arquivo de perfil corrompido, componente qBittorrent ausente, contrato de versão/esquema incompatível e destino ocupado. A incompatibilidade foi injetada no manifesto; não foi feita uma migração real nem um downgrade. Os testes da chave incorreta/ausente e do pacote corrompido invocam a restauração antes de existir o volume de destino.
- A remoção deliberada de `prowlarr.db` da cópia restaurada foi detectada pelo erro específico `RESTORED_INDEXER_LOST`; a validação não refez o cadastro. Esse destino defeituoso foi encerrado e mantido para revisão. Uma falha de backup provocada por link sintético preservou a origem e deixou um pacote sem `COMPLETE`, recusado na verificação seguinte. Destinos ocupados foram recusados e seus registros/perfis continuaram legíveis.
- Cinco testes do pacote aprovados verificam travessia, caminhos absolutos/Windows, links simbólicos e físicos, dispositivo, duplicatas, pai que é arquivo, exclusão de bytes de mídia e autenticação AES-GCM mesmo com fingerprint da chave adulterado. O perfil contendo somente `config.xml` é recusado como incompleto.
- Contrato do inicializador: 83 verificações aprovadas no Windows PowerShell 5.1 e outras 83 no PowerShell 7. Verificador de versões: 4 testes aprovados. Não houve alteração no backend ou na interface; foram compilados e usados como serviços reais no ensaio. Os jobs normais de backend/frontend continuam no CI.
- Não foram usados `.env`, banco, chave, perfis, mídia ou serviços da instalação diária. Não foram testados guarda externa, transporte/importação de volumes, falha de disco, perda total do Docker Desktop, reprodução audiovisual ou migração entre releases. As primeiras execuções detectaram problemas de preparação da fixture, nomes de dono/grupo, preservação do setgid e etiquetas herdadas das imagens; a prova só foi aceita depois das correções, sem relaxar a comparação dos dados.


## Correções da revisão da PR #36

- Helpers sem logging do Docker, inclusive nos canais que devolvem manifesto e dump. O teste mantém apenas o contêiner sintético de prova por tempo suficiente para inspecionar `LogConfig.Type=none`, confirma a entrega dos dados ao chamador e verifica que eles não aparecem em `docker logs`.
- A guarda de uso ativo compara montagens por origem e sobreposição, incluindo binds; não usa o destino do contêiner como substituto da origem. O ensaio deve recusar o escritor no mesmo bind antes de criar os volumes do pacote e aceitar uma origem não relacionada montada no mesmo `/config`.
- `volume-subpath` é recusado antes da coleta. A revisão demonstrou que omiti-lo copiava a raiz errada; o procedimento permanece limitado a volumes inteiros e binds, sem ampliar o escopo da mídia.
- Sinal e prazo de parada entram no manifesto e no `docker create`. A comparação do destino deve confirmar os 1800 segundos do qBittorrent, sem depender do `--timeout 60` usado apenas para encerrar os serviços pequenos da fixture. Pacotes sem esses metadados são recusados, sem inferir valores ausentes.

Evidência local da correção em 13/11/2026: execução `4264753262` aprovada, com resultado sintético em `data/backup-validation-4264753262/result.json` (ignorado pelo Git). Foram comprovadas as duas restaurações com o perfil Prowlarr originado de bind novo, recusa de escritor externo no mesmo bind antes de criar o pacote, aceitação de origem não relacionada no mesmo `/config`, recusa de subpaths iguais/diferentes, entrega íntegra do manifesto/dump com `LogConfig.Type=none`, ausência desse conteúdo em `docker logs`, preservação dos sinais/prazos dos cinco serviços e dos 1800 segundos do qBittorrent. Pacote com metadado de parada removido foi recusado antes de criar o volume de destino. Permaneceram aprovados os cenários anteriores de corrupção, chave, ocupação, perda do banco Prowlarr sem autorreparo e falha recuperável.

Sete testes de contrato do comando e cinco testes do pacote aprovados. O contrato do inicializador voltou a passar com 83 verificações em Windows PowerShell 5.1 e outras 83 em PowerShell 7. A primeira preparação do novo escritor de teste expôs uma corrida de prontidão; o roteiro passou a esperar o arquivo próprio criado pelo processo, sem afrouxar a recusa nem alterar os dados usados como prova. A guarda também trata destinos de montagem normalizados; sua regressão cobre `Target=/data/`.

O CI da revisão reproduz os testes de contrato e o ensaio real. Não há publicação de pacotes, chaves, perfis ou snapshots em seus artefatos.
