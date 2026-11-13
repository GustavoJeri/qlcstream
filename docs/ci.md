# Integração contínua

O workflow `.github/workflows/ci.yml` executa em pull requests para `develop` e `main`, em pushes nessas branches e manualmente pelo GitHub Actions. Há dois resultados independentes: **Backend** (Java 21, `./mvnw verify`) e **Frontend** (Node 26, `npm ci`, testes e compilação). Falhas em qualquer etapa fazem seu job falhar.

O frontend usa o lockfile; o backend usa o Maven Wrapper. O workflow possui permissão de leitura do repositório, não conserva credenciais no checkout, fixa as ações por commit e limita a duração dos jobs. Execuções anteriores da mesma PR ou branch são canceladas quando outra é iniciada. Relatórios de teste do backend e saídas de teste ou compilação do frontend são guardados por sete dias quando um job falha, quando disponíveis. Não são incluídos `.env`, banco, arquivos de mídia ou configurações pessoais de serviços. As verificações não precisam de credenciais das integrações nem iniciam downloads.

Para repetir localmente, use Java 21 e Node 26. No backend, execute `./mvnw verify` (Windows: `.\mvnw.cmd verify`). No frontend, execute `npm ci`, `npm test -- --watch=false` e `npm run build`. `npm ci` recria `node_modules`; para preservar dependências locais, use uma cópia de trabalho isolada.

O CI informa resultados; para impedir merges sem aprovação dos checks, configure regras de proteção de branch no GitHub. Não há implantação ou publicação automática neste workflow.

O job **Windows launcher** executa `docs/validation/Test-WindowsLauncher.ps1` no Windows PowerShell 5.1 e PowerShell 7, sem instalar dependências. Cobre classificação de falhas, preservação de configuração, preparação inicial, reutilização, portas TCP/UDP e bloqueio concorrente. Docker/HTTP/navegador são simulados; sockets e arquivos são reais. A prova com Docker Desktop, documentada em [inicialização no Windows](inicializacao-windows.md), é uma validação local separada e não faz parte deste job.
