# Configuração persistente das integrações — Issue #17

As versões verificadas e a conferência administrativa estão na [matriz das integrações](versoes-integracoes.md). Fixar imagens não muda a precedência, a adoção explícita ou a chave de proteção descritas neste documento.

O backend permite preparar a configuração pela API. O [assistente visual da Issue #18](assistente-primeira-configuracao.md) usa estes contratos. Esta API é administrativa e local, como o restante da instalação atual: mantenha as portas do Compose vinculadas a loopback; não a publique na internet. Não há autenticação administrativa nova nesta entrega.

## Precedência e atualização

O [inicializador do Windows](inicializacao-windows.md) preserva `.env`, chave e configurações gerenciadas. Mudanças administrativas no ambiente exigem aplicação explícita ao backend; o uso diário não recria contêineres para aplicar variáveis novas.

Cada integração começa com fonte `environment`, usando as propriedades existentes de TMDB, Prowlarr e qBittorrent. Nenhum valor é copiado automaticamente para o banco. O `.env` continua funcionando e não é alterado.

Um PUT com `source: application` adota explicitamente a configuração gerenciada daquela integração. Campos omitidos são copiados da configuração efetiva atual nessa ação. Depois disso, o registro gerenciado inteiro tem precedência, inclusive campos vazios: não existe mistura automática com o ambiente. Mudanças posteriores no ambiente não alteram esse registro. `source: environment`, sozinho, remove o registro gerenciado e volta explicitamente ao ambiente carregado na inicialização.

Cada PUT é atômico por integração. Validação, cifragem e commit precisam concluir antes de publicar a nova configuração em memória. Falha mantém o retrato anterior; não há atualização parcial de campos ou clientes. PUTs são serializados. O último PUT concluído prevalece; a API ainda não oferece edição condicional por versão. Operações iniciadas antes da troca terminam com seu retrato anterior; as seguintes usam o novo retrato. A aplicação suporta **um backend proprietário deste banco**, sem sincronização entre réplicas.

Salvar não exige disponibilidade do serviço remoto e não confirma autenticação. Use o teste separado antes de salvar quando desejar essa confirmação. Credenciais recusadas podem ser salvas por decisão explícita e aparecerão como recusadas no diagnóstico. Não se executam downloads para validar configurações.

## API

Todas as respostas usam `Cache-Control: no-store`. IDs: `tmdb`, `prowlarr`, `qbittorrent`.

| Operação | Resultado |
| --- | --- |
| `GET /api/system/integration-settings` | Fonte, endereço, presença de usuário/segredo e configuração mínima por integração. Nunca retorna usuário, token, chave ou senha. |
| `PUT /api/system/integration-settings/{id}` | Atualiza explicitamente a integração e retorna apenas seu estado seguro. |
| `POST /api/system/integration-settings/{id}/test` | Testa um candidato com os mesmos probes autenticados dos diagnósticos. Retorna `id`, `configured` e `reason`, sem persistir, mudar clientes ou substituir o diagnóstico efetivo. |

Exemplo com valores fictícios para Prowlarr:

```json
{
  "source": "application",
  "baseUrl": "http://prowlarr:9696",
  "secret": {"action": "replace", "value": "credencial-ficticia"}
}
```

TMDB usa `secret` para o token; Prowlarr para a chave; qBittorrent para a senha, com `username` no mesmo corpo. No teste, `source` pode ser omitido. Campos não secretos omitidos preservam os atuais; texto vazio limpa explicitamente esses campos. Endereços aceitam HTTP/HTTPS, incluindo subpastas, sem usuário/senha, query ou fragmento.

- Omitir `secret` (ou enviar `null`) preserva o segredo atual.
- `{"secret":{"action":"replace","value":"novo-valor"}}` substitui; texto vazio é rejeitado.
- `{"secret":{"action":"clear"}}` limpa explicitamente, sem voltar ao segredo do ambiente.
- `{"source":"environment"}` restaura a fonte de ambiente; não aceita outros campos junto.
- Erros de entrada retornam 400 com `invalid_configuration`; indisponibilidade de chave ou persistência retorna 503 com `settings_unavailable`. Não retornam corpos remotos nem mensagens de exceções.

Os endpoints anteriores `/api/system/integrations` e `/check` são preservados. Trocar a configuração invalida o resultado anterior e impede publicar uma tentativa antiga. O diagnóstico de armazenamento usa também o qBittorrent efetivo, inclusive após atualização.

## Chave, backup e recuperação

Os registros de `integration_settings` contêm a configuração completa cifrada com AES-256-GCM, nonce aleatório por gravação e autenticação vinculada ao ID da integração. A chave **não fica no banco**. `INTEGRATION_SETTINGS_KEY_FILE` aponta para um arquivo com 32 bytes aleatórios codificados em Base64. Fora do Compose, use um arquivo com acesso restrito ao usuário do backend.

No Compose principal, a pasta `data/integration-secrets` é protegida e montada para permitir a criação explícita da chave pelo backend. O assistente pode provisionar uma chave ausente mediante ação explícita. Como alternativa administrativa, antes de adotar a configuração gerenciada, provisione `data/integration-secrets/integration-settings.key`. Para gerar somente quando não existe, no PowerShell:

```powershell
$keyDirectory = Join-Path (Get-Location) 'data/integration-secrets'
New-Item -ItemType Directory -Force -Path $keyDirectory | Out-Null
$keyPath = Join-Path $keyDirectory 'integration-settings.key'
if (Test-Path -LiteralPath $keyPath) { throw 'A chave já existe; preserve-a.' }
$keyBytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
$keyStream = [System.IO.File]::Open($keyPath, [System.IO.FileMode]::CreateNew)
try {
  $keyText = [System.Text.Encoding]::ASCII.GetBytes([Convert]::ToBase64String($keyBytes))
  $keyStream.Write($keyText)
} finally { $keyStream.Dispose() }
```

Restrinja as permissões desse arquivo e do diretório aos administradores e ao processo do backend. Nunca use a chave fictícia de `docs/validation` em uma instalação pessoal. A pasta `data` é ignorada pelo Git. Não imprima a chave nem a inclua em registros, capturas ou chamados de suporte.

A chave fica vinculada ao processo na primeira leitura para recuperar ou gravar uma configuração gerenciada. Antes de cada nova gravação cifrada, o backend confere o arquivo contra essa chave. Se ele mudar, faltar ou se tornar inválido, a gravação é recusada com 503 e os registros e clientes atuais são preservados. Restaurar o arquivo original permite retomar as gravações. Isso impede misturar registros cifrados com chaves diferentes; não constitui um mecanismo de rotação.

Instalações que só usam ambiente não precisam da chave. Se houver registro gerenciado e a chave faltar, for diferente ou o conteúdo estiver adulterado, o backend recusa iniciar: não retorna silenciosamente ao ambiente. Para recuperar, restaure **o backup do banco e a chave correspondente**, mantendo as mesmas permissões e o caminho configurado, e reinicie o backend. Guarde a chave em backup protegido separado do banco, com teste periódico de restauração. Perder a chave impede recuperar os segredos cifrados.

Se não houver cópia recuperável, a decisão de abandonar os registros deve ser explícita: com o backend parado e depois de fazer backup do banco, remova somente as linhas escolhidas de `integration_settings` por administração do PostgreSQL. Ao iniciar novamente, essas integrações usarão o ambiente. Reconfigure-as conscientemente pela API; não apague outras tabelas, volumes ou mídia. Rotação automática da chave não faz parte desta entrega: trocar o arquivo sozinho torna os registros existentes ilegíveis.

O assistente coleta novos segredos apenas na memória do formulário, sem armazenamento persistente no navegador. Consumidores futuros devem mantê-los apenas durante a entrada/teste, sem persistir em localStorage, sessionStorage, URLs ou logs. Evite ferramentas que gravem histórico dos corpos das requisições com credenciais reais.

## Validação reproduzível isolada

Na raiz, sem carregar o `.env` pessoal:

```powershell
docker compose --env-file docs/validation/integrations.env -f docs/validation/integrations-compose.yaml up -d --build
python docs/validation/test-integrations.py
```

A fixture `qlcstream-integrations-test` usa banco/volume próprios, chave e credenciais declaradamente fictícias, servidor HTTP controlado sem operações de download, API na porta 18087 e interface na porta 14217. O roteiro confere ambiente, candidato válido/recusado sem persistência, clientes reais contra serviços simulados, segredo omitido, diagnósticos, reinício do backend com PostgreSQL preservado, limpeza e recuperação explícitas. Encerre só essa fixture com `docker compose --env-file docs/validation/integrations.env -f docs/validation/integrations-compose.yaml down`; o banco de teste fica preservado.

## Evidências de 13/11/2026

- Java 21/Linux: **64 testes aprovados**, sem falhas nem testes ignorados. Incluem precedência, adoção/reset explícitos, candidato sem persistência, segredo omitido, limpeza, reinício, falha no commit, chave ausente/incorreta, adulteração, respostas sem segredos, aplicação aos clientes e descarte de diagnóstico anterior durante troca concorrente.
- Node 26: **19 testes da interface aprovados** e imagem de produção compilada. Nenhuma entrada ou persistência de segredos foi acrescentada à interface.
- Backend Spring Boot e PostgreSQL reais na fixture: roteiro aprovado, inclusive após renomear temporariamente somente a tabela da fixture para provocar falha real de gravação. A API retornou 503 e conservou a configuração efetiva; a tabela foi restaurada antes do reinício. Valores persistidos foram recuperados após reiniciar somente o backend de teste. Nenhum download foi solicitado.
- TMDB, Prowlarr e qBittorrent foram simulados com respostas controladas e credenciais fictícias. Não foi comprovada interoperabilidade com contas ou serviços pessoais; os testes anteriores dos protocolos foram preservados. Não houve validação visual nova do assistente, que permanece fora do escopo.
- A execução inicial restrita no Windows não conseguiu abrir conexões locais; uma execução com acesso local passou e ignorou um teste de permissões específico de Unix. A evidência completa acima usa Linux e não tem testes ignorados. Os serviços, banco, configurações e mídia de uso diário não foram usados.

Na revisão da PR #32, foram acrescentadas regressões para troca da chave durante a execução e para endereços com barras finais. O cliente qBittorrent e os diagnósticos de conexão e armazenamento compartilham a montagem dos endpoints, preservando subpastas e removendo barras finais antes de acrescentar o caminho da API. Isso também se aplica às configurações existentes por ambiente, sem reescrevê-las.

Após essas correções, `mvn verify` em Java 21/Linux aprovou **66 testes**, sem falhas nem testes ignorados, e gerou o pacote do backend. A regressão da chave cobre tanto a primeira gravação quanto o carregamento após reinício; a regressão de endereços consulta conexão, preferências e lista vazia de torrents em seis combinações de raiz/subpasta e barras finais, sem iniciar downloads.


O [procedimento de backup e restauração](backup-restauracao.md) reúne dump lógico, perfis completos, chave original em guarda separada e mídia opcional. A recuperação cria destino isolado novo, exige compatibilidade e não aplica rollback Flyway nem substitui a instalação ativa.
