# Comportamento dos downloads neste fork

## Pedido repetido

Se já existe um registro da mesma referência de aquisição, dentro do mesmo filme ou série, o aplicativo devolve esse registro. Cancelamentos pendentes também impedem nova submissão dessa referência. A consulta e a submissão são serializadas no PostgreSQL para reduzir duplicatas quando os pedidos chegam ao mesmo tempo.

Essa regra não comprova que duas fontes com nomes e referências diferentes contêm o mesmo vídeo.

## Transferência sem registro

Uma inclusão no qBittorrent pode acontecer antes de a transação do banco terminar. Se essa transação falha, a transferência continua no motor. Ao repetir um magnet, o aplicativo pode recuperar uma transferência de hash idêntico, hexadecimal ou Base32. Um nome igual não substitui um hash diferente. URLs sem identidade verificável e com nome já existente são recusadas para revisão no motor; não há adoção silenciosa por título. Uma pasta já vinculada a outro registro também é recusada.

Somente pastas dentro de `/downloads/` são aceitas nessa recuperação. Não há movimentação automática de arquivos.

## Cancelamento

Cancelar registra uma intenção persistente e pede a remoção da transferência vinculada no qBittorrent, usando `deleteFiles=false`. Os arquivos já salvos e os registros da biblioteca são preservados. A resposta ao comando aceita a solicitação; não certifica remoção imediata. A fila mostra **Cancelamento pendente** até o motor confirmar ausência do hash conhecido.

Se ainda não existe hash no registro, a busca usa exclusivamente uma correspondência única da pasta daquele pedido. A intenção permanece após reinício e continua sendo reconciliada: uma URL aceita com HTTP 202 pode produzir o torrent mais tarde. Não se expira essa intenção só por uma consulta vazia ou pelo tempo decorrido. Duas correspondências não são resolvidas escolhendo arbitrariamente a primeira; confira o motor para remover a ambiguidade. Um pedido duplicado não pode cancelar a transferência original apenas porque ambos têm o mesmo título.

## Sincronização e recuperação

Registros em erro continuam sendo consultados. Uma identidade conhecida não é substituída por outra na mesma pasta. A associação por pasta ocorre somente sem hash e com correspondência única. Uma tentativa comum sem vínculo por mais de cinco minutos passa a erro; um cancelamento pendente conserva sua intenção. “Retomar” em um erro solicita reverificação e retomada ao motor.

Uma falha de descoberta de vídeos pela biblioteca não remove mais o torrent. A descoberta é tentada novamente.

## Exclusão de um arquivo

Na biblioteca, a confirmação informa o arquivo exato e o efeito sobre a transferência: o torrent vinculado será removido sem apagar os outros arquivos. Episódios e temporadas oferecem ações por arquivo, sem exclusão recursiva de pastas. Um pedido explícito de exclusão é registrado antes de qualquer mudança no motor ou nos bytes. Se a remoção do torrent ainda está pendente, o arquivo permanece e a tela orienta repetir a ação.

Depois de confirmar a transferência removida, o backend exclui somente o arquivo e marca o registro DELETED. Se o banco falha depois da remoção dos bytes, a mensagem informa essa etapa e a intenção persistida permite repetir para concluir a marcação. A descoberta não reativa um arquivo marcado DELETED nem um registro com exclusão solicitada. Ausência de arquivo, por si só, não cria uma intenção de exclusão. Repetir um pedido já concluído é inócuo.

Os diretórios são percorridos sem seguir links, por descritores presos à raiz e aos pais. O Java usa `SecureDirectoryStream` quando disponível; o runtime Alpine inclui um auxiliar isolado, com `open(..., dir_fd=..., O_NOFOLLOW)` e `unlink(..., dir_fd=...)`. Não há comando shell construído a partir do nome do arquivo. Trocar um pai por link entre validação e uso não redireciona a operação. Backend nativo Windows sem esse suporte recusa a exclusão; o aplicativo Windows com backend Docker Linux usa o auxiliar incluído na imagem.

Novas subpastas do motor usam `UMASK=0002` e herdam o grupo 1000. A imagem oficial documenta a variável [UMASK](https://github.com/qbittorrent/docker-qbittorrent-nox#tweaking-the-options). Destinos existentes não são corrigidos recursivamente; uma recusa de permissão exige revisão administrativa. A migração V12 acrescenta a intenção de exclusão; não há promessa de rollback para esquema anterior. A aplicação dessa revisão à instalação diária continua sendo uma operação administrativa separada.

No desktop, as mensagens específicas de arquivo ausente ou reprodutor indisponível aparecem sem esconder a lista. No navegador, as ações nativas são ocultadas e a tela orienta seguir o caminho relativo a partir da pasta de mídia escolhida na instalação.

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
