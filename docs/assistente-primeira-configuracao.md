# Assistente de primeira configuração — Issue #18

As imagens do Compose e os limites de compatibilidade estão na [matriz das integrações](versoes-integracoes.md). O assistente testa as conexões; conferir a versão e a revisão da imagem é uma ação administrativa separada. Configurações existentes continuam exigindo adoção explícita.

No Windows, use o [inicializador oficial](inicializacao-windows.md) `iniciar-qlcstream.bat` para chegar ao aplicativo sem exigir credenciais prévias. O inicializador não provisiona a chave nem altera integrações.

Em **Configurar** (`/setup`), o aplicativo oferece etapas para catálogo, busca de fontes, motor e pasta. Um aviso convida a conferir configurações incompletas e problemas na pasta; o catálogo continua acessível quando busca ou downloads são adiados. Filmes, temporadas e episódios explicam a indisponibilidade das ações e apontam para o assistente.

## Preservação e retomada

Ao abrir, o assistente consulta os valores persistidos e seleciona a primeira integração sem configuração mínima. Se todas estiverem preenchidas, abre a pasta. É possível voltar a qualquer etapa para revisar. A retomada depende do estado do servidor, sem gravar progresso ou credenciais no navegador. Etapas opcionais adiadas continuam pendentes na próxima abertura.

Nenhuma configuração é salva ao abrir ou navegar. Uma integração por ambiente pode ser mantida sem qualquer PUT. Para adotar a fonte gerenciada, confirme a opção explícita e clique em **Salvar e continuar**. Segredos salvos e usuários não são retornados; campos vazios preservam os valores existentes. Um segredo digitado substitui o anterior somente ao salvar. Valores não salvos são descartados ao trocar de etapa ou sair, conforme o aviso da tela.

**Testar sem salvar** usa o candidato no endpoint separado da Issue #17 e mostra sucesso, autenticação recusada, configuração ausente, endereço inválido ou falha de conexão. Salvar e testar são ações distintas. Uma falha de gravação conserva os campos; se houver falha de comunicação, consulte o estado do servidor antes de repetir, pois a resposta pode ter sido perdida após um commit.

## Proteção no primeiro uso

A tela oferece **Criar chave de proteção** quando a chave não está disponível. A ação chama `POST /api/system/integration-settings/key`; `GET` no mesmo endereço retorna apenas `ready`. A chave nunca passa pela API. Ela é gerada com aleatoriedade criptográfica no servidor, em arquivo temporário exclusivo com permissão 0600 nos sistemas POSIX, sincronizada em disco e publicada por hard link sem substituir o destino. O arquivo temporário é removido ao final. O sistema de arquivos precisa suportar hard links; falhas são apresentadas sem expor conteúdo ou caminhos.

O Compose prepara a pasta protegida para o usuário do backend e permite a criação explícita da chave. Prepara também `incoming` somente quando ausente, sem alterar conteúdo, proprietário ou permissões de um destino já existente. A configuração de mídia anterior e seu volume são preservados. Preparar diretórios não cria a chave: é necessária a ação explícita na tela ou a provisão administrativa anterior.

A pasta nova usa proprietário UID 100 e grupo compartilhado GID 1000, com modo 2770. O backend mantém seu usuário/grupo próprios e recebe o grupo suplementar 1000; o motor usa `qbtUser` 1000:1000 na imagem atual. O bit setgid mantém o grupo em novos arquivos e subpastas; as permissões desses arquivos também dependem do umask de quem os cria. Isso permite escrita de ambos no destino e leitura dos arquivos de mídia pelo backend, sem abrir a pasta para outros usuários. A pasta de segredos continua separada e privada. Instalações que personalizam a identidade do motor precisam alinhar o grupo compartilhado. Diretórios existentes, inclusive os que tenham permissões incompatíveis, não são corrigidos silenciosamente: devem ser revisados pelo administrador.

Arquivo inválido existente não é substituído. Se uma chave já tiver sido usada pelo processo ou houver configurações gerenciadas, uma chave ausente não é regenerada. O bloqueio de mudança de chave da PR #32 permanece. Banco com configurações gerenciadas sem chave correspondente continua recusando a inicialização. Consulte [backup e recuperação](configuracao-integracoes.md). Fora do Compose, o administrador deve fornecer uma pasta protegida gravável ou provisionar a chave previamente.

## Pasta e limites

Salvar a pasta altera somente o destino de novos downloads e executa o diagnóstico já existente. A tela explica problemas de acesso e incompatibilidade do motor. Caminhos compatíveis mantêm a equivalência física **inconclusiva**. O assistente não cria diretórios arbitrários, não altera montagens, não move mídia, não instala indexadores, não cadastra contas e não inicia downloads. Fontes e credenciais continuam sendo escolhas explícitas.

A detecção de integração configurada usa presença dos campos, sem prometer disponibilidade. O teste autenticado e a tela de diagnósticos continuam sendo a confirmação pontual de conexão. Para habilitar ações de download, a interface exige também diagnóstico de pasta compatível; isso não comprova equivalência física dos volumes. O backend continua sendo uma instalação administrativa local, com um processo por banco e sem nova autenticação administrativa.

## Validação isolada

Na raiz, sem usar o ambiente pessoal:

```powershell
docker compose --env-file docs/validation/integrations.env -f docs/validation/setup-compose.yaml up -d --build
python docs/validation/test-setup.py
```

A fixture `qlcstream-setup-test` usa PostgreSQL, chave e mídia sintética em volumes próprios, com APIs simuladas sem operações de inclusão de torrents. Interface: `http://127.0.0.1:14218/setup`; API: porta 18088. O primeiro uso de um volume novo começa sem chave e sem credenciais. O roteiro é repetível: restaura somente configurações da fixture para ambiente, provisiona a chave de forma idempotente e usa credenciais fictícias.

Para conferir pela tela: crie a chave; teste um token fictício inválido e depois `fictional_candidate` contra `http://mock:8000`; confirme adoção e salve TMDB. Use o catálogo com busca/motor adiados. Reinicie somente o backend da fixture e reabra o assistente: a etapa pendente deve reaparecer. Complete Prowlarr e qBittorrent com o mesmo endereço e segredo fictício, usando `fictional_user` no motor. Confira `missing` como pasta inválida e recupere com `incoming`. Reabra para revisar: os campos de segredo ficam vazios.

Encerre apenas a fixture, preservando os seus volumes:

```powershell
docker compose --env-file docs/validation/integrations.env -f docs/validation/setup-compose.yaml down
```

## Evidências de 13/11/2026

- Java 21/Linux: **67 testes aprovados**, sem falhas nem testes ignorados, e pacote do backend gerado. Provisão explícita, idempotência, recusa de substituição, recuperação e regressões anteriores preservadas.
- Node 26: **31 testes aprovados** e compilação de produção aprovada. Cobrem adoção explícita, candidato sem gravação, preservação de formulário após erro, segredo omitido, retomada, adiamento, pasta inválida/recuperada e descarte de respostas antigas.
- Arquivo de chave em pasta de teste do workspace montada no Docker: publicação por hard link conferida com o usuário do backend; teste de provisão também aprovado usando esse sistema de arquivos. A pasta de segredos real não foi usada.
- Roteiro de API aprovado contra Spring Boot e PostgreSQL reais na fixture, incluindo reinício, candidato inválido, chave idempotente, catálogo independente e recuperação da pasta.
- Edge na fixture limpa: conferidos criação explícita da chave, credencial recusada sem salvar, candidato válido, adoção, adiamento de busca/downloads, catálogo acessível, retomada após reinício, recuperação após indisponibilidade temporária do backend, etapas restantes, pasta inválida e recuperação, e revisão de configuração salva sem revelar segredos. O diagnóstico manteve o compartilhamento físico inconclusivo.
- As APIs externas foram simuladas; contas pessoais e interoperabilidade com serviços externos reais não foram testadas. Nenhum download foi iniciado. Banco, serviços, configurações e mídia da instalação diária foram preservados.

### Correção de permissões após revisão da PR #33

Execute `python docs/validation/test-shared-access.py` na raiz. O roteiro usa o mesmo script de preparação do Compose principal, as imagens reais do backend e do qBittorrent e um projeto aleatório `qlcstream-access-test-*`, com volumes descartáveis e sem rede. Somente shells são executados: a API, o banco e o processo de torrents não são iniciados. Ao concluir, o roteiro remove apenas os volumes que acabou de criar.

Resultado aprovado: backend UID 100, GID 101 e grupo suplementar 1000; motor UID/GID 1000:1000. Ambos criaram arquivos e diretórios sintéticos, leram os arquivos um do outro e percorreram subpastas. `incoming` nova foi conferida como 100:1000 e modo 2770. Uma segunda preparação preservou bytes, proprietário, grupo e modo de um destino existente e de uma chave sintética; um link existente e seu destino também foram preservados. Nenhuma mídia, configuração ou chave pessoal foi montada. Essa verificação cobre permissões de acesso; não inicia um download nem muda o resultado inconclusivo do diagnóstico de equivalência física.

A entrega foi revisada e integrada em `develop` pela PR #33; a Issue #18 foi encerrada. A publicação em `main` é posterior.
