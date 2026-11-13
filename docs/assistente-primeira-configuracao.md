# Assistente de primeira configuração — preparação da Issue #18

Estado: iniciado, ainda sem assistente visual. As dependências #15, #16 e #17 estão integradas em `develop`. Esta preparação acrescenta os contratos de consulta, atualização e teste de candidato ao serviço HTTP da interface, preservando as operações anteriores de diagnóstico.

## Fluxo a implementar

1. Consultar o estado no servidor e oferecer a configuração quando incompleta, mantendo acesso ao catálogo quando TMDB estiver pronto.
2. Apresentar catálogo, busca, motor e pasta em etapas, com instruções para obter credenciais e escolher fontes explicitamente.
3. Carregar somente estado e campos públicos; nunca preencher um campo de segredo com conteúdo salvo. Segredo novo fica somente na memória do formulário e é descartado após salvar ou sair.
4. Testar candidatos sem salvar; salvar exige ação distinta, incluindo confirmação para adotar a fonte gerenciada quando a instalação usa ambiente.
5. Derivar a retomada dos valores persistidos, oferecendo adiar busca/downloads sem bloquear o catálogo. Reutilizar os diagnósticos de conexão e armazenamento, sem declarar compartilhamento físico comprovado quando for inconclusivo.
6. Explicar nos detalhes de filmes e séries por que busca/downloads estão indisponíveis e oferecer acesso à etapa correspondente.

## Primeiro uso da chave

A API da #17 exige arquivo de chave externo ao banco antes de persistir configurações gerenciadas. Isso ainda não pode ser resolvido pela interface: falta uma operação explícita para provisionar a chave numa instalação limpa, ou uma provisão segura na inicialização da instalação. Essa decisão precisa ser implementada e validada nesta etapa para cumprir o aceite de não editar arquivos. Não gerar nem substituir uma chave existente silenciosamente. Preservar a proteção contra troca de chave entregue na revisão da PR #32.

## Validação necessária para concluir a Issue

- Instalação limpa e instalação configurada por ambiente, sem sobrescrita automática.
- Candidato aceito/recusado, erro de comunicação e falha de persistência sem perda do formulário.
- Voltar, adiar etapas opcionais, reabrir e retomar após reinício do backend.
- Pasta inválida e recuperação; armazenamento inconclusivo não equivale a pronto.
- Segredos ausentes de URLs, respostas, logs e armazenamento do navegador.
- Fluxo visual e testes completos com serviços controlados e credenciais fictícias, sem iniciar downloads.

A Issue #18 permanece aberta até implementar o fluxo completo, publicar a PR e concluir a revisão.

Preparação validada em 13/11/2026: 23 testes da interface aprovados em Node 26, incluindo quatro novos testes do contrato HTTP; compilação de produção aprovada. Nenhuma etapa visual do assistente foi validada nesta preparação.
