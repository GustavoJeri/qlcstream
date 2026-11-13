# Diagnóstico das conexões — Issue #15

Conectividade autenticada não certifica a revisão da imagem. Consulte a [matriz e o verificador de versão](versoes-integracoes.md) para comparar a aplicação executada e a referência esperada; essa conferência separada não atualiza serviços.

Em **Configurações**, o aplicativo verifica as conexões com TMDB, Prowlarr e qBittorrent. Campos preenchidos não comprovam disponibilidade. Cada linha mostra o resultado, uma orientação de recuperação e o horário da última tentativa concluída. O contador de conexões verificadas exige resultados bem-sucedidos dentro da validade. Após a integração da Issue #16, três conexões disponíveis não bastam para anunciar “Tudo pronto”: a pasta tem um [diagnóstico separado](diagnostico-armazenamento.md), que mantém o compartilhamento físico inconclusivo sem prova. A verificação das conexões não valida indexadores nem fontes de download.

## Contrato da API

`GET /api/system/integrations` retorna um array, com um item por serviço. Na primeira consulta ou após vencer a validade, inicia verificações em segundo plano e retorna imediatamente. Cada serviço conclui independentemente. `POST /api/system/integrations/check` solicita uma nova tentativa, respeitando o intervalo mínimo e reaproveitando uma tentativa já em andamento. Ambas as respostas usam `Cache-Control: no-store`.

Campos de cada item:

| Campo | Significado |
| --- | --- |
| `id` | `tmdb`, `prowlarr` ou `qbittorrent` |
| `configured` | Presença dos campos obrigatórios; não indica disponibilidade |
| `status` | `unknown`, `not_configured`, `checking`, `available`, `authentication_rejected` ou `unavailable` |
| `reason` | Motivo estável, sem dados privados, listado abaixo |
| `checkedAt` | Instante UTC da última tentativa concluída, ou `null` |
| `expiresAt` | Fim da validade daquele resultado, ou `null` |
| `retryAfter` | Primeiro instante UTC permitido para solicitar outra tentativa, ou `null` |

Durante `checking`, os horários da tentativa anterior podem permanecer para referência; o resultado anterior não deve ser apresentado como disponível. Antes de uma tentativa concluída, `checkedAt` é `null`. `unknown` representa ausência de resultado; a primeira leitura normalmente já o substitui por `checking` ou `not_configured`.

Motivos: `missing_configuration`, `checking`, `unknown`, `ok`, `authentication_rejected`, `invalid_configuration`, `connection_failed`, `timeout`, `invalid_response` e `http_error`. O frontend traduz esses motivos em orientações específicas por serviço. Não recebe endereço, credencial, corpo remoto nem mensagem de exceção.

HTTP 401/403 é classificado como autenticação/acesso recusado, o que também pode indicar bloqueio no serviço ou proxy. `Fails.` do login do qBittorrent também indica autenticação recusada. Endereço sintaticamente inválido produz `invalid_configuration`; serviço desligado, falha de DNS ou TLS produzem `connection_failed`. Uma URL válida apontando para outro site pode produzir `invalid_response` ou `http_error`. Esses resultados não identificam com certeza a causa física da falha; a tela indica o que conferir.

## Consultas e preservação dos downloads

- TMDB: `GET /configuration`, com Bearer token. Exige JSON com `images` objeto e `change_keys` array.
- Prowlarr: `GET /api/v1/system/status`, com `X-Api-Key`. Exige JSON com versão não vazia. Não pesquisa indexadores.
- qBittorrent: `POST /api/v2/auth/login` seguido de `GET /api/v2/app/version`, usando cookies apenas no cliente de diagnóstico. Aceita login legado `200 + Ok.` e atual `204`. A versão deve ter formato de versão do aplicativo.

Os endereços usam as mesmas propriedades das integrações, incluindo subpastas. Aceitam HTTP/HTTPS sem usuário/senha, query ou fragmento na URL. Redirecionamentos não são seguidos, evitando encaminhar credenciais para outro destino. Os clientes de catálogo, busca e downloads permanecem independentes. O diagnóstico não chama operações de torrents, não consulta mídia nem altera o banco de dados. O único POST remoto cria a sessão autenticada do qBittorrent.

Referências dos contratos: [TMDB](https://developer.themoviedb.org/reference/configuration-details), [Prowlarr](https://prowlarr.com/docs/api/) e [qBittorrent](https://github.com/qbittorrent/qBittorrent/wiki/WebUI-API-%28qBittorrent-5.0%29).

## Limites e atualização

- Conexão HTTP: até **2 segundos**. Cada requisição: até **2 segundos** para obter a resposta.
- Orçamento total por serviço: **5 segundos**, incluindo leitura do corpo e as duas operações do qBittorrent. A espera do futuro HTTP usa o restante desse orçamento e cancela a requisição se excedido.
- Os três serviços executam em tarefas independentes. Nenhuma chamada externa é feita segurando o bloqueio do cache; a API devolve o retrato atual sem esperar a rede.
- Validade: **60 segundos** após concluir cada tentativa, inclusive falhas. Resultados ficam somente em memória e são perdidos ao reiniciar o backend.
- Intervalo mínimo entre inícios: **10 segundos por serviço**. Cliques repetidos e outras abas reutilizam o resultado ou a tarefa atual. Não há sobreposição de tarefas do mesmo serviço; a publicação confere a identidade da tentativa atual.
- A interface consulta o retrato a cada **500 ms apenas enquanto houver `checking`**, com espera máxima de **8 segundos** para o ciclo e limite inicial de 6 segundos para acessar o backend. Para de consultar quando os serviços concluem ou ocorre erro.
- Um relógio local atualiza a apresentação a cada segundo, sem chamadas de rede. Ao vencer o resultado, a linha mostra “Desatualizado”, retira “Tudo pronto” e pede nova tentativa. Reabrir a página também atualiza resultados vencidos.
- Uma nova sequência cancela a assinatura anterior; um contador de sequência impede que respostas antigas substituam as mais recentes. Ao sair da página, assinaturas e temporizadores são encerrados.

Falha entre interface e backend mostra “Não confirmado” e orientação para conferir o backend. Não é apresentada como autenticação recusada nas integrações. O horário anterior permanece visível, mas não confirma disponibilidade. O botão de nova tentativa volta a funcionar após o intervalo mínimo, inclusive se o último retrato estava em andamento.

## Validação automatizada

Na pasta `backend`, execute `./mvnw test` (Windows: `.\mvnw.cmd test`). Os testes de diagnóstico usam servidores HTTP locais e credenciais fictícias para sucesso autenticado, login legado/204, autenticação recusada, conexão interrompida, timeout, corpo lento, JSON inválido, redirecionamento, erro HTTP, configuração ausente, recuperação e contrato MVC sem segredos. Também verificam independência das tarefas, cache, intervalo mínimo e renovação após vencer a validade. A suíte inclui as regressões anteriores dos downloads, incluindo resumos de inclusão JSON/202.

Na pasta `frontend`, com Node compatível com Angular 22 (preferencialmente Node 26), execute `npm test -- --watch=false` e `npm run build`. Os testes da tela cobrem progresso independente, expiração, recuperação, falha de comunicação com backend, intervalo mínimo, limite de polling e descarte de resposta antiga.

Validação desta entrega em 12/11/2026: **40 testes de backend** (16 de diagnóstico e 24 anteriores), **12 testes de frontend** e compilação de produção aprovada com Node 26. A compilação mantém avisos de tamanho de estilos em outras telas, sem falhas. Na prévia isolada abaixo, foi conferido no navegador o ciclo de sucesso → Prowlarr desligado (2/3 prontos) → Prowlarr religado (3/3 prontos), além de horários e expiração após um minuto. A instalação de uso diário e os serviços reais não foram usados para provocar falhas.

## Conferência visual isolada no Windows

O utilitário de teste `IntegrationDiagnosticsPreview` liga apenas servidores de loopback nas portas **18080, 18081 e 18082**, com credenciais fictícias. Usa o controller real com Spring MVC de teste, o serviço de diagnóstico real e servidores HTTP controlados. Serve a interface compilada; não inicializa a aplicação completa, banco, agendadores ou clientes de downloads. Não aponta para a instalação de uso diário. Outros endpoints da interface recebem respostas fictícias; esse utilitário serve exclusivamente para conferir o diagnóstico.

Depois de compilar a interface, na pasta `backend`:

```powershell
.\mvnw.cmd test-compile dependency:build-classpath '-Dmdep.outputFile=target/preview-classpath.txt'
$previewClasspath = 'target/test-classes;target/classes;' + (Get-Content -Raw target/preview-classpath.txt).Trim()
java -cp $previewClasspath dev.qlcstream.system.IntegrationDiagnosticsPreview ../frontend/dist/frontend/browser
```

Abra `http://127.0.0.1:18080/settings`. Confira os três serviços disponíveis e os horários. Em outro terminal:

```powershell
Invoke-RestMethod -Method Post http://127.0.0.1:18080/test/prowlarr/stop
```

Após o intervalo mínimo, clique em **Testar novamente**. Prowlarr deve ficar indisponível com orientação de conexão; TMDB e qBittorrent continuam disponíveis. Religue o servidor controlado:

```powershell
Invoke-RestMethod -Method Post http://127.0.0.1:18080/test/prowlarr/start
```

Teste novamente após o intervalo mínimo e confira a recuperação das três conexões verificadas. Espere um minuto sem atualizar e confira “Desatualizado”. A prévia de conexões não oferece o endpoint de diagnóstico de armazenamento: essa seção pode informar que não foi possível diagnosticar e o resumo não deve anunciar “Tudo pronto”. Para validar a pasta, use a instalação isolada completa descrita no guia de armazenamento. Encerre a prévia com Ctrl+C; isso fecha somente os servidores de teste.

## Configuração efetiva

Desde a Issue #17, clientes e diagnósticos usam a mesma [configuração efetiva](configuracao-integracoes.md). Alterações invalidam o cache e impedem publicar resultados da configuração anterior. Testar um candidato não altera o diagnóstico efetivo.
