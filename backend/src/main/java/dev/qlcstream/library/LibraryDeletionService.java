package dev.qlcstream.library;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LibraryDeletionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final LibraryTransferControl controls;
    private final Path root;

    public LibraryDeletionService(JdbcTemplate jdbc, TransactionTemplate transaction, LibraryTransferControl controls,
            @Value("${qlc-stream.storage.media-root:/data}") String root) {
        this.jdbc = jdbc; this.transaction = transaction; this.controls = controls; this.root = Path.of(root);
    }

    // The installation contract has one backend. Serialize retries of a destructive action.
    public synchronized void delete(long id) {
        var item = jdbc.query("SELECT relative_path, download_id, availability, deletion_requested FROM local_file WHERE id = ?",
                rs -> rs.next() ? new Item(rs.getString(1), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getBoolean(4)) : null, id);
        if (item == null) throw new Failure(HttpStatus.NOT_FOUND, "Arquivo não encontrado.");
        if (item.availability().equals("DELETED")) return; // Safe retry after a lost successful response.
        if (!item.availability().equals("PRESENT") && !item.requested())
            throw new Failure(HttpStatus.CONFLICT, "Arquivo indisponível. Nenhuma exclusão foi autorizada anteriormente.");
        boolean fileRemoved = false;
        boolean fileAttempted = false;
        try (var file = LibraryFileDeletion.open(root, item.path())) {
            // Commit authorization before touching the engine or bytes. Discovery must not revive this record.
            transaction.executeWithoutResult(status -> jdbc.update(
                    "UPDATE local_file SET deletion_requested = true WHERE id = ?", id));
            if (item.download() != null && !controls.cancelForFileDeletion(item.download())) {
                throw new Failure(HttpStatus.CONFLICT,
                        "Cancelamento da transferência solicitado. O arquivo foi mantido; aguarde e repita a exclusão para confirmar a remoção do torrent.");
            }
            fileAttempted = true;
            file.delete();
            fileRemoved = true;
            transaction.executeWithoutResult(status -> jdbc.update(
                    "UPDATE local_file SET availability = 'DELETED', last_verified_at = now() WHERE id = ?", id));
        } catch (UnsupportedOperationException unsupported) {
            throw new Failure(HttpStatus.CONFLICT,
                    "Este sistema não oferece exclusão segura de arquivos. Use o backend no Docker Linux; o arquivo foi mantido.");
        } catch (DataAccessException failure) {
            throw new Failure(HttpStatus.SERVICE_UNAVAILABLE, fileRemoved
                    ? "O arquivo foi removido, mas a biblioteca não pôde ser atualizada. Repita a exclusão para concluir o registro."
                    : "A biblioteca não pôde registrar a exclusão. O arquivo foi mantido; confira o servidor e tente novamente.");
        } catch (LibraryTransferControl.Unavailable failure) {
            throw new Failure(HttpStatus.BAD_GATEWAY,
                    "Não foi possível confirmar a remoção da transferência. O arquivo foi mantido; confira o motor e repita a exclusão.");
        } catch (IOException | IllegalArgumentException failure) {
            throw new Failure(HttpStatus.CONFLICT, fileRemoved
                    ? "O arquivo foi removido. Não foi possível concluir a operação; confira a biblioteca antes de repetir."
                    : fileAttempted
                    ? "A transferência vinculada foi tratada, mas a exclusão do arquivo não pôde ser confirmada. Confira as permissões e repita para reconciliar a biblioteca."
                    : "O arquivo foi mantido. Confira as permissões e se o caminho permanece dentro da biblioteca, sem links de diretórios.");
        }
    }

    private record Item(String path, UUID download, String availability, boolean requested) { }
    static final class Failure extends RuntimeException {
        final HttpStatus status;
        Failure(HttpStatus status, String detail) { super(detail); this.status = status; }
    }
}
