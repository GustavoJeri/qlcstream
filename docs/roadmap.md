# Evolução incremental do fork

Objetivo: usar o QLC Stream com downloads confiáveis e reduzir a configuração manual do primeiro acesso. O projeto original e sua autoria são preservados; melhorias futuras podem ser propostas ao upstream em mudanças pequenas.

## Entregue — F00: confiabilidade dos downloads

- [x] Cancelar tentativas órfãs sem afetar outra transferência.
- [x] Voltar a acompanhar downloads em erro.
- [x] Evitar pedidos repetidos durante submissões concorrentes.
- [x] Aceitar respostas de inclusão do qBittorrent 5.3.
- [x] Recuperar transferências que ficaram sem registro no aplicativo.
- [x] Exibir os erros das ações.
- [x] Cobrir os fluxos com testes e validar inclusão de fonte nova.

Detalhes: [histórico](../CHANGELOG.md) e [comportamento dos downloads](downloads-confiabilidade.md).

## Próximo incremento — F01: diagnóstico real das conexões

O diagnóstico de conexões da Issue #15 consulta as APIs autenticadas de leitura e apresenta estados independentes na interface. Limites, contrato e roteiro de validação estão em [diagnóstico das integrações](diagnostico-integracoes.md). A validação de pastas pertence à Issue #16.

Entregar:

- [x] Distinguir configuração ausente, conexão indisponível, autenticação recusada e conexão funcionando.
- [x] Testar TMDB, Prowlarr e qBittorrent com limites de tempo.
- [x] Explicar na tela a ação necessária para cada falha.
- [x] Diagnosticar leitura e escrita do backend na pasta salva e comparar destinos do motor, mantendo o compartilhamento físico inconclusivo sem prova.
- [x] Ocultar valores de credenciais nas respostas e mensagens do diagnóstico.

Aceite: endereço incorreto, senha inválida e serviço desligado produzem diagnósticos diferentes; a tela só declara um serviço pronto depois de uma consulta bem-sucedida.

## Entregue — F02: assistente de primeira configuração

- [x] Detectar primeiro acesso e oferecer configuração por etapas.
- [x] Reaproveitar a instalação existente, sem sobrescrever configurações.
- [x] Configurar e validar catálogo, busca, motor e pasta pela interface.
- [x] Explicar a obtenção das credenciais e permitir testar cada conexão antes de continuar.
- [x] Guardar credenciais no servidor e retornar somente seu estado à interface.
- [x] Permitir catálogo sem exigir que todas as integrações estejam prontas.

Aceite: uma pessoa configura uma instalação limpa seguindo apenas as orientações da tela, sem editar arquivos do projeto. A seleção de fontes de busca continua sendo uma decisão explícita do usuário.

## Entregue — F03: inicialização mais simples no Windows

- [x] Um ponto de entrada para verificar requisitos e iniciar o aplicativo.
- [x] Detectar Docker indisponível, portas ocupadas e falhas de inicialização.
- [x] Criar a configuração inicial somente quando ela não existir.
- [x] Mostrar o progresso e abrir o aplicativo quando estiver saudável.
- [x] Oferecer instruções recuperáveis quando uma etapa falhar.

Aceite: iniciar uma instalação já configurada não exige editar arquivos ou reconstruir imagens sem necessidade.

## F04: atualizações previsíveis

- [ ] Definir versões suportadas das integrações e substituir imagens flutuantes por versões testadas (implementação da Issue #20 em revisão; matriz real documentada).
- [ ] Executar testes e compilação automaticamente em cada alteração.
- [ ] Validar instalação limpa e atualização com dados existentes.
- [ ] Documentar cópia de segurança e recuperação antes de migrar dados.
- [ ] Publicar versões identificáveis com notas de alteração.

Aceite: cada versão publicada informa o que mudou, o que foi testado e como recuperar a instalação anterior.

## F05: experiência diária

- [ ] Distinguir claramente cancelar uma transferência de apagar seus arquivos.
- [ ] Facilitar abrir a pasta e o reprodutor externo.
- [ ] Melhorar mensagens de falta de fontes, arquivo ausente e download pendente.
- [ ] Medir os pontos de atrito antes de incluir novas funcionalidades.

## Como executar os incrementos

Cada incremento deve ter um objetivo verificável, uma mudança pequena, testes do comportamento alterado e validação do fluxo completo. Fazer primeiro uma instalação de teste evita experimentar mudanças sobre a biblioteca de uso diário.

Não publicar arquivos `.env`, credenciais, configurações pessoais dos serviços, banco de dados ou mídia. Manter os créditos do projeto original. A escolha de uma licença para o projeto de origem cabe ao seu autor; este fork não acrescenta uma licença em nome dele.

Contrato, limitações e validação com volumes próprios estão em [diagnóstico de armazenamento](diagnostico-armazenamento.md).

A persistência das integrações da Issue #17 foi revisada e integrada em `develop` pela [PR #32](https://github.com/GustavoJeri/qlcstream/pull/32): [contrato, API e recuperação](configuracao-integracoes.md). O assistente da Issue #18 foi integrado pela PR #33: [fluxo, preservação e evidências](assistente-primeira-configuracao.md). O inicializador da Issue #19 foi integrado pela [PR #34](https://github.com/GustavoJeri/qlcstream/pull/34), com CI aprovado e Issue encerrada: [contrato, falhas e validação](inicializacao-windows.md). A Issue #20 continua o inventário `671246a` com [referências fixadas, matriz real e procedimento de promoção/recuperação](versoes-integracoes.md), em revisão antes de integrar. Publicação em `main` e atualização da instalação diária são etapas posteriores.
