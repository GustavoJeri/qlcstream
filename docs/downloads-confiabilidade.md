# Comportamento dos downloads neste fork

## Pedido repetido

Se já existe um registro ativo da mesma fonte ou do mesmo título de release, dentro do mesmo filme ou série, o aplicativo devolve esse registro. A consulta e a submissão são serializadas no PostgreSQL para reduzir duplicatas quando os pedidos chegam ao mesmo tempo.

Essa regra não comprova que duas fontes com nomes e referências diferentes contêm o mesmo vídeo.

## Transferência sem registro

Uma inclusão no qBittorrent pode acontecer antes de a transação do banco terminar. Se essa transação falha, a transferência continua no motor. Ao repetir o pedido, o aplicativo procura a transferência pelo título completo da fonte ou pelo hash de um magnet, reaproveita a pasta existente e recupera o registro.

Somente pastas dentro de `/downloads/` são aceitas nessa recuperação. Não há movimentação automática de arquivos.

## Cancelamento

Cancelar retira o registro da lista e pede a remoção da transferência vinculada no qBittorrent, usando `deleteFiles=false`. Os arquivos já salvos são preservados.

Se ainda não existe hash no registro, a busca usa exclusivamente a pasta daquele pedido. Um pedido duplicado não pode cancelar a transferência original apenas porque ambos têm o mesmo título.

## Sincronização e recuperação

Registros em erro continuam sendo consultados. A associação usa primeiro o hash e depois a pasta. Uma tentativa sem vínculo por mais de cinco minutos passa a erro em vez de permanecer indefinidamente na fila. “Retomar” em um erro solicita reverificação e retomada ao motor.

Uma falha de descoberta de vídeos pela biblioteca não remove mais o torrent. A descoberta é tentada novamente.

## Compatibilidade

O cliente usa `stop`, `start` e `recheck` e aceita respostas legadas de sucesso, HTTP 204 e os resumos JSON de inclusão com pelo menos um sucesso ou uma operação pendente. Um resumo que indica somente falhas continua sendo recusado.

A referência de inclusão do qBittorrent 5.3 está no [código oficial de `addAction`](https://github.com/qbittorrent/qBittorrent/blob/release-5.3.0/src/webui/api/torrentscontroller.cpp).

## Verificação

O teste `DownloadRegressionTest` cobre cancelamento, recuperação, duplicação, estados e contratos HTTP com um servidor de teste. Ele inclui a submissão de uma fonte nova com resposta 202 e a persistência do registro, evitando validar apenas o caminho de um pedido já existente.

Executar no diretório `backend`:

```text
./mvnw verify
```

No Windows, usar `mvnw.cmd verify`. O frontend pode ser compilado com `npm ci` e `npm run build`, usando uma versão de Node suportada pelo Angular do projeto.
