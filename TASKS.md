

# Tarefas

## Mover paginação e ordenação do catálogo para o backend

- [x] Definir um contrato paginado para filmes e séries com:
  - `items` ou `results`;
  - `page`;
  - `totalPages`;
  - `totalResults`;
  - `hasNext`.
- [x] Propagar os metadados de paginação retornados pelo TMDB no backend.
- [x] Aceitar no backend os parâmetros de página, ordenação e filtros do catálogo.
- [x] Aplicar a ordenação no backend antes de montar a resposta:
  - popularidade;
  - nota;
  - lançamento mais recente;
  - lançamento mais antigo;
  - título.
- [x] Preservar a relevância original do TMDB para resultados de busca textual, salvo quando o usuário escolher explicitamente outra ordenação.
- [x] Garantir que a ordenação seja estável entre páginas e que uma série/filme não apareça repetido ao carregar a próxima página.
- [x] Atualizar os contratos e serviços Angular para consumir a resposta paginada.
- [x] Remover a ordenação global feita no frontend quando ela duplicar a responsabilidade do backend.
- [x] Atualizar o botão de carregamento incremental para usar `hasNext` em vez de inferir o fim pela quantidade de itens retornados.
- [ ] Adicionar testes de backend para paginação, ordenação e busca.
- [ ] Adicionar testes de frontend para acumulação de páginas, troca de filtro e prevenção de duplicatas.
- [ ] Validar manualmente filmes e séries com busca, filtros, ordenação e carregamento de várias páginas.

### Critérios de aceite

- A página 2 não altera a ordem dos itens já exibidos na página 1.
- O frontend exibe exatamente a ordem enviada pelo backend.
- O botão de próxima página desaparece quando `hasNext` é falso.
- Não há duplicatas ao alternar busca, filtro, ordenação e carregamento incremental.
- A busca textual mantém a relevância do TMDB por padrão.

## Melhorar eficiência das buscas

- [x] Manter debounce no autocomplete e nas buscas do catálogo.
- [x] Cancelar requisições anteriores quando uma nova consulta substituir a anterior.
- [ ] Evitar chamadas repetidas para a mesma consulta, idioma, filtro, ordenação e página durante uma sessão.
- [x] Normalizar consultas antes de criar chaves de cache: espaços, maiúsculas/minúsculas e parâmetros equivalentes.
- [ ] Separar o limite do autocomplete do limite da busca completa.
- [ ] Definir timeouts, tratamento de rate limit do TMDB e mensagens de erro consistentes.
- [x] Evitar disparar buscas de filmes, séries e pessoas quando o texto ainda não atingiu o tamanho mínimo.
- [ ] Adicionar métricas de tempo de resposta, taxa de cache hit/miss e quantidade de requisições canceladas.

## Cache de catálogo

- [ ] Adicionar cache no backend para respostas do TMDB com TTL configurável.
- [ ] Usar chaves compostas por endpoint, consulta, idioma, filtro, ordenação e página.
- [ ] Definir TTLs diferentes para trending/discover, buscas textuais e detalhes de títulos.
- [ ] Invalidar ou atualizar entradas quando houver falha de autenticação, resposta inválida ou mudança de configuração do TMDB.
- [ ] Limitar tamanho, quantidade de entradas e concorrência do cache para evitar crescimento sem controle.
- [ ] Evitar chamadas duplicadas simultâneas para a mesma chave usando coalescência de requisições.
- [ ] Não armazenar tokens, dados sensíveis ou respostas maiores que o limite definido.
- [ ] Adicionar testes de cache hit, expiração, erro do provedor e concorrência.
- [ ] Medir latência, volume de chamadas ao TMDB e redução de tráfego antes e depois.

## Otimizar imagens

### Objetivo de desempenho

- [ ] Fazer as imagens aparecerem o mais rapidamente possível, priorizando a imagem visível na primeira tela e carregando o restante sob demanda.
- [ ] Definir metas de desempenho para imagens: primeira imagem visível sem atraso perceptível, LCP de imagem dentro da meta definida e ausência de downloads grandes desnecessários.
- [ ] Tentar manter todas as imagens na maior qualidade possível, quando for possível.

- [x] Centralizar a construção das URLs do TMDB em um helper com perfis de tamanho por uso.
- [x] Escolher tamanhos compatíveis com o espaço renderizado: card, detalhe, elenco, episódio e hero.
- [x] Usar `loading="lazy"` e `decoding="async"` em listas, grids, elenco, recomendações e episódios.
- [x] Reservar `fetchpriority="high"` para o backdrop hero visível na primeira pintura.
- [x] Adicionar `srcset` e `sizes` para imagens responsivas quando houver ganho real.
- [ ] Evitar carregar vários backdrops do carrossel ao abrir a página; pré-carregar no máximo o próximo item.
- [ ] Definir dimensões ou `aspect-ratio` estável para reduzir layout shift.
- [ ] Padronizar placeholders e tratamento de erro para imagens ausentes ou indisponíveis.
- [x] Configurar cache de longo prazo para assets próprios do frontend no Nginx.
- [ ] Medir bytes transferidos, quantidade de imagens, LCP e CLS antes e depois da otimização.

### Critérios de aceite de eficiência

- Consultas idênticas não geram chamadas repetidas desnecessárias durante o TTL ou a sessão.
- Uma nova busca não permite que a resposta de uma consulta antiga contamine os resultados atuais.
- O cache reduz chamadas ao TMDB sem exibir dados expirados além do TTL configurado.
- A primeira tela carrega somente as imagens prioritárias e as demais entram sob demanda.
- As imagens não causam deslocamento perceptível de layout e mantêm qualidade adequada ao tamanho exibido.
- A imagem principal aparece rapidamente em conexões limitadas e as imagens fora da viewport não bloqueiam a primeira tela.
- Nenhuma imagem é baixada em resolução muito maior que o espaço em que será exibida.
