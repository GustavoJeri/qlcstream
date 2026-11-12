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

Hoje `/api/system/integrations` verifica se os campos estão preenchidos. Isso não comprova que os serviços estão acessíveis nem que as credenciais funcionam. A tela pode informar disponibilidade mesmo sem uma conexão válida.

Entregar:

- [ ] Distinguir configuração ausente, conexão indisponível, autenticação recusada e conexão funcionando.
- [ ] Testar TMDB, Prowlarr e qBittorrent com limites de tempo.
- [ ] Explicar na tela a ação necessária para cada falha.
- [x] Diagnosticar leitura e escrita do backend na pasta salva e comparar destinos do motor, mantendo o compartilhamento físico inconclusivo sem prova.
- [ ] Ocultar valores de credenciais nas respostas e mensagens.

Aceite: endereço incorreto, senha inválida e serviço desligado produzem diagnósticos diferentes; a tela só declara um serviço pronto depois de uma consulta bem-sucedida.

O diagnóstico de armazenamento da Issue #16 é independente do diagnóstico das conexões. Contrato, limitações e validação com volumes próprios estão em [diagnóstico de armazenamento](diagnostico-armazenamento.md).

## F02: assistente de primeira configuração

- [ ] Detectar primeiro acesso e oferecer configuração por etapas.
- [ ] Reaproveitar a instalação existente, sem sobrescrever configurações.
- [ ] Configurar e validar catálogo, busca, motor e pasta pela interface.
- [ ] Explicar a obtenção das credenciais e permitir testar cada conexão antes de continuar.
- [ ] Guardar credenciais no servidor e retornar somente seu estado à interface.
- [ ] Permitir catálogo sem exigir que todas as integrações estejam prontas.

Aceite: uma pessoa configura uma instalação limpa seguindo apenas as orientações da tela, sem editar arquivos do projeto. A seleção de fontes de busca continua sendo uma decisão explícita do usuário.

## F03: inicialização mais simples no Windows

- [ ] Um ponto de entrada para verificar requisitos e iniciar o aplicativo.
- [ ] Detectar Docker indisponível, portas ocupadas e falhas de inicialização.
- [ ] Criar a configuração inicial somente quando ela não existir.
- [ ] Mostrar o progresso e abrir o aplicativo quando estiver saudável.
- [ ] Oferecer instruções recuperáveis quando uma etapa falhar.

Aceite: iniciar uma instalação já configurada não exige editar arquivos ou reconstruir imagens sem necessidade.

## F04: atualizações previsíveis

- [ ] Definir versões suportadas das integrações e substituir imagens flutuantes por versões testadas.
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
