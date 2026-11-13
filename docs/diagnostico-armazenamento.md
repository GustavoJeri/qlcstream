# Diagnóstico de armazenamento — Issue #16

As [imagens fixadas e a matriz de compatibilidade](versoes-integracoes.md) foram verificadas com o compartilhamento UID 100/grupo 1000 já entregue. Essa prova vale para as fixtures sintéticas; não amplia a conclusão do diagnóstico HTTP sobre volumes de outras instalações.

Em **Configurações → Pasta dos downloads**, o botão **Diagnosticar pasta salva** verifica o destino persistido no servidor. A página também verifica ao abrir e depois de salvar outro destino. Alterar o texto sem salvar não altera o destino testado. Ações de salvar e testar não se sobrepõem na página. O resultado mostra horário e orienta repetir após mudanças nos volumes ou permissões.

## Contrato

`POST /api/system/storage/check` retorna `status`, `backend`, `mapping`, `sharedDirectoryVerified` e `checkedAt`, com `Cache-Control: no-store`. Não retorna raiz, caminhos privados, preferências completas, respostas remotas ou credenciais. A verificação é síncrona e serializada no backend.

- A raiz efetiva é `qlc-stream.storage.media-root` (`MEDIA_ROOT`), com padrão `/data`, igual à usada para descobrir arquivos da biblioteca. No Compose principal continua sendo `/data`. Antes, a biblioteca ignorava essa propriedade e usava `/data` fixamente; agora biblioteca e diagnóstico usam a mesma raiz. Fora do Docker, configure uma raiz absoluta correspondente à mídia. Alterar essa raiz ou o volume físico não é uma operação de migração suportada por esta entrega.
- A pasta salva é relativa à raiz. Rejeita caminhos absolutos Unix/Windows, UNC, prefixos de unidade, destino vazio após normalização e travessia que escape da raiz. Resolve links existentes e rejeita destinos cujo caminho real fique fora da raiz. Raiz e destino precisam existir e ser diretórios; o teste não os cria.
- Confere leitura, escrita e acesso ao diretório pelo usuário do backend. Cria arquivo exclusivo com `CREATE_NEW`, escreve conteúdo aleatório, reposiciona o arquivo e lê os mesmos bytes. Remove apenas seu próprio arquivo. Usa operações relativas ao diretório quando o provedor oferece `SecureDirectoryStream`; nos demais, usa `NOFOLLOW_LINKS` e `DELETE_ON_CLOSE`, com limpeza vinculada ao arquivo aberto. Falha de leitura, escrita ou limpeza nunca produz comprovação de sucesso. Não percorre a biblioteca nem abre mídia existente.
- O motor é consultado com login e `GET /api/v2/app/preferences`, sem comandos de torrent. O orçamento HTTP total é cinco segundos, incluindo corpo das respostas; aceita login legado `200 + Ok.` e `204`. Redirecionamentos não são seguidos. Usa a [API oficial do qBittorrent](https://github.com/qbittorrent/qBittorrent/wiki/WebUI-API-%28qBittorrent-5.0%29).
- Compara `save_path` e, quando habilitado, `temp_path`, com a raiz esperada `/downloads`, por componentes, rejeitando travessia. Não exige que o destino padrão seja igual à subpasta salva: o aplicativo já envia explicitamente `/downloads/<pasta salva>/<id>` nos novos downloads. Não altera preferências do motor nem inspeciona categorias ou todos os torrents.

| Resultado | Significado |
| --- | --- |
| `backend: read_write_verified` | Escrita, leitura e limpeza do arquivo próprio concluídas no destino salvo. |
| `backend: missing_directory / not_directory` | Raiz ou destino ausente, ou arquivo onde deveria existir diretório. |
| `backend: permission_denied / io_error` | Permissões insuficientes ou teste de arquivo não concluído. |
| `backend: outside_root` | Caminho inválido ou link que escapa da raiz. |
| `backend: configuration_unavailable` | Não foi possível ler a configuração persistida. |
| `mapping: mapping_incompatible` | Destino padrão ou temporário informado pelo motor fora de `/downloads`. |
| `mapping: compatible_unproven` | Caminhos compatíveis; equivalência física não comprovada. |
| `mapping: engine_not_configured / engine_authentication_failed / engine_unavailable` | Comparação indisponível; a interface explica como recuperar. |

`status` é `blocked` quando o backend não comprova acesso ou o motor informa destino incompatível. Com acesso local comprovado e sem incompatibilidade conhecida, é `inconclusive`. `sharedDirectoryVerified` permanece `false`: a API do motor não permite ler o arquivo temporário escrito pelo backend. Nomes de diretórios iguais, preferências e até o Compose esperado não comprovam a montagem efetiva de outra instalação. A interface não anuncia “Tudo pronto” a partir desse resultado.

## Preservação e limites

O Compose principal continua montando a mesma origem `MEDIA_HOST_PATH` em `/data` no backend e `/downloads` no motor. Confira a origem efetivamente montada nos dois serviços. Pasta indisponível não autoriza remover torrents ou registros. O diagnóstico não move, substitui, migra ou exclui mídia; também não reverifica torrents ou seleciona outro disco.

Salvar outro diretório atualiza somente `storage_root.download_relative_path`, usado por novos downloads. Arquivos e transferências anteriores conservam seus caminhos relativos à raiz; seus registros não são reescritos. Manter o mesmo volume e a mesma raiz preserva o acesso anterior.

O teste é pontual e não garante espaço para um download inteiro, escrita pelo usuário do motor, permissões de cada mídia, destinos particulares de torrents existentes nem disponibilidade futura. A montagem e os diretórios devem ser administrados por pessoas confiáveis; nos provedores sem operações relativas seguras, mudanças concorrentes de links/montagens durante a verificação não têm proteção transacional. Não altere montagens durante o teste.

## Validação isolada reproduzível

Na raiz do repositório:

```powershell
docker compose -f docs/validation/storage-compose.yaml up -d --build
./docs/validation/Test-Storage.ps1
```

Use somente essa instalação de teste. Ela tem nome `qlcstream-storage-test`, banco e volumes próprios e não carrega o `.env` pessoal. A interface fica em `http://localhost:14216/settings`, e a API em `http://localhost:18086`. O motor não publica porta e participa apenas de uma rede interna sem acesso externo; nenhum download é iniciado. A permissão de acesso por sub-rede ao motor é exclusiva dessa fixture descartável e não deve ser usada em produção. A prévia usa um proxy próprio que preserva a porta do `Host` para requisições da mesma origem.

O roteiro cria apenas conteúdo sintético nos volumes nomeados da fixture. Compara o arquivo próprio nos dois contêineres, testa pasta ausente, permissão real `000`, link externo e preferência incompatível. Depois muda para um destino válido e confirma que o arquivo anterior continua legível e seu registro continua retornando em `/api/library`. O registro sintético é marcado como vídeo para exercitar essa consulta; não é um filme reproduzível. Verifica também zero torrents e ausência de resíduos dos probes. Ao terminar, restaura as preferências de teste e a pasta `incoming`. A fixture reinicializa somente sua própria configuração ao subir novamente.

Para encerrar a fixture preservando suas evidências: `docker compose -f docs/validation/storage-compose.yaml down`. Esse comando não encerra o projeto principal.

## Evidências de 12/11/2026

- Java 21 em Linux/Alpine: **37 testes aprovados**, sem testes ignorados, incluindo 13 novos e as 21 regressões dos downloads. Cobertura de caminhos externos portáveis, link simbólico, pasta/raiz ausentes, permissão recusada, incompatibilidade, resultado inconclusivo, preservação e contrato HTTP sem caminhos privados.
- Node 26: **13 testes de interface aprovados** (7 novos); compilação de produção aprovada. Permanecem os avisos preexistentes de tamanho de estilos de outras páginas.
- Fluxo completo com backend, PostgreSQL, qBittorrent e Nginx reais na fixture: todos os cenários do roteiro aprovados. Arquivo gravado pelo contêiner do motor e lido pelo backend com SHA-256 `d88b739da472189845e8842d095d9806b7adef3290024a5d95f73c8f992b7e78`. Registro anterior e bytes preservados após mudar o destino; zero torrents e nenhum probe residual.
- Edge na prévia isolada: conferidos resultado inconclusivo, pasta ausente, permissão recusada, link externo junto de destino incompatível e recuperação ao salvar `next`. Textos e horário conferidos na tela.
- A prova física acima vale somente para a fixture inspecionada. O resultado do diagnóstico permaneceu corretamente `inconclusive`, com `sharedDirectoryVerified: false`. Credenciais, banco, mídia e contêineres da instalação diária não foram alterados.

A implementação original partiu de `develop` sem depender da PR #28 (Issue #15). Na integração das duas entregas, o resumo em Configurações passou a contar somente conexões disponíveis com resultado recente. Mesmo com três conexões verificadas, o armazenamento permanece inconclusivo sem prova física. As duas suítes de interface são preservadas em arquivos separados e verificam esse comportamento combinado.

Na revisão para integrar as PRs #28 e #30, a suíte combinada passou com **53 testes de backend e 19 de interface**, sem perdas de cobertura, e a compilação de produção foi aprovada. O roteiro de volumes foi repetido no aplicativo combinado: todos os cenários passaram, o arquivo e o registro anteriores foram preservados e o motor permaneceu sem torrents. O diagnóstico autenticado de conexão com esse motor também foi conferido; TMDB e Prowlarr permaneceram sem configuração nessa fixture. Os testes controlados das conexões cobrem esses serviços sem usar credenciais pessoais.

O diagnóstico do motor acompanha a [configuração efetiva das integrações](configuracao-integracoes.md), incluindo alterações pela API sem reiniciar.
