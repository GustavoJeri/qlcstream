# Continuidade do trabalho no repositório original

## Bases da integração

O desenvolvimento conjunto passa a ser organizado em [diegodinizm1/qlcstream](https://github.com/diegodinizm1/qlcstream), preservando a autoria e os commits dos dois históricos. O fork [GustavoJeri/qlcstream](https://github.com/GustavoJeri/qlcstream) permanece disponível como referência das Issues, PRs, revisões e evidências anteriores.

Na preparação desta integração, a branch `integration/qlcstream` do upstream estava em `de42e11f831d9330e726159d20c8e345d82737e1`, com a [PR #1](https://github.com/diegodinizm1/qlcstream/pull/1) aberta para `main`. Ela já continha as entregas do fork até `aa18d2b` e mudanças próprias de catálogo, downloads e desktop. O `develop` do fork estava em `35e78058ee0599bc5aebe882247e698a4cc36ce7`, incluindo backup/restauração e o inventário da distribuição versionada.

A branch complementar integra esses dois pontos por merge, sem squash, rebase ou substituição da branch do Diego. Isso conserva os commits e seus autores. A aprovação da PR complementar deve anteceder a revisão final da PR #1, pois acrescenta uma funcionalidade e um job de CI. O workflow também passa a executar para PRs destinadas a `integration/qlcstream` e para pushes nessa branch.

## Mapa de Issues

| Trabalho | Origem preservada | Continuidade no upstream |
| --- | --- | --- |
| Versões e atualização recuperável | [#22](https://github.com/GustavoJeri/qlcstream/issues/22) | [#2](https://github.com/diegodinizm1/qlcstream/issues/2) |
| Cancelamento e exclusão de arquivo | [#23](https://github.com/GustavoJeri/qlcstream/issues/23) | [#3](https://github.com/diegodinizm1/qlcstream/issues/3) |
| Abertura de arquivos e pastas no Windows | [#24](https://github.com/GustavoJeri/qlcstream/issues/24) | [#4](https://github.com/diegodinizm1/qlcstream/issues/4) |
| Estados e dados desatualizados dos downloads | [#25](https://github.com/GustavoJeri/qlcstream/issues/25) | [#5](https://github.com/diegodinizm1/qlcstream/issues/5) |
| Plano de evolução | [#26](https://github.com/GustavoJeri/qlcstream/issues/26) | [#6](https://github.com/diegodinizm1/qlcstream/issues/6) |

As Issues concluídas #14 a #21 e todas as PRs anteriores permanecem no fork. Números de Issues/PRs em documentos históricos referem-se ao fork, salvo quando houver indicação explícita do upstream. O [plano original](https://github.com/GustavoJeri/qlcstream/issues/26) reúne as entregas e suas evidências. O [roadmap](roadmap.md) registra o estado alcançado no fork, que não implica publicação em `main` do upstream.

O GitHub só permite transferência nativa de Issues entre repositórios do mesmo proprietário. Por isso, as cinco pendências foram recriadas com links para os registros originais e referências históricas qualificadas. A numeração, autoria e datas dos novos registros são próprias; comentários e eventos originais continuam disponíveis na origem. Não foram encerradas as pendências originais nem duplicadas as Issues concluídas. Fonte: [documentação oficial de transferência de Issues](https://docs.github.com/en/issues/tracking-your-work-with-issues/administering-issues/transferring-an-issue-to-another-repository), consultada em 13/11/2026.

## Próximas entregas

1. Revisar e validar a integração complementar de backup/restauração sobre as mudanças do Diego. Os resultados anteriores do fork não certificam automaticamente essa combinação.
2. Reavaliar os critérios das Issues upstream #3, #4 e #5 diante da PR #1, especialmente cancelamento sem exclusão, estados da fila e abertura no Windows. Não refazer funcionalidades já presentes nem concluir critérios sem evidência.
3. Continuar a investigação da distribuição e atualização recuperável na Issue upstream #2, usando [o inventário existente](versoes-atualizacao.md). Ainda não há certificação de release, atualização entre versões ou rollback de esquema.
4. Usar o plano upstream #6 como acompanhamento das novas entregas e manter os links de origem para consulta do histórico. Novas PRs devem vincular os números do upstream explicitamente, evitando encerrar por engano uma Issue do fork.

Não há promoção automática para `main`, alteração de proteção de branch, lançamento ou atualização da instalação diária neste procedimento. Testes de banco, chave, perfis e mídia continuam restritos a serviços sintéticos isolados. Configurações pessoais, pacotes de backup e evidências privadas não fazem parte da integração Git.
