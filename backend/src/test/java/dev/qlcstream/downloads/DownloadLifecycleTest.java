package dev.qlcstream.downloads;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.qlcstream.downloads.application.*;
import dev.qlcstream.downloads.infrastructure.persistence.*;
import dev.qlcstream.downloads.infrastructure.qbittorrent.*;
import dev.qlcstream.downloads.infrastructure.web.DownloadUpdatePublisher;

class DownloadLifecycleTest {
    @Test void reconcilesLateTorrentAfterPendingCancellationWithoutDeletingBytes() {
        var row = DownloadRegressionTest.row(); var engine = new DownloadRegressionTest.Engine();
        var repo = DownloadRegressionTest.repository(row);
        var synchronizer = new QbittorrentDownloadSynchronizer(repo, engine, null, new DownloadUpdatePublisher());
        new DownloadControlService(repo, engine).control(row.id(), DownloadControlService.Action.CANCEL);
        assertEquals("CANCELED", row.status());
        synchronizer.synchronize(); assertEquals("CANCELED", row.status());
        engine.snapshots = List.of(DownloadRegressionTest.snapshot("downloading", .2, "/downloads/incoming/test"));
        synchronizer.synchronize();
        assertEquals(List.of("remove:hash"), engine.commands); assertEquals("CANCELED", row.status());
        engine.snapshots = List.of(); synchronizer.synchronize(); assertEquals("REMOVED", row.status());
    }
    @Test void ambiguousPathsCannotAcquireAnIdentityOrReceiveCommands() {
        var row = DownloadRegressionTest.row(); var engine = new DownloadRegressionTest.Engine();
        engine.snapshots = List.of(DownloadRegressionTest.snapshot("downloading", .2, "/downloads/incoming/test"),
                new QbittorrentClient.TorrentSnapshot("other", "downloading", .3, 1000, 300, 0, 0, "/downloads/incoming/test"));
        new QbittorrentDownloadSynchronizer(DownloadRegressionTest.repository(row), engine, null, new DownloadUpdatePublisher()).synchronize();
        assertNull(row.infoHash()); assertTrue(engine.commands.isEmpty());
    }
    @Test void missingKnownHashDoesNotAdoptAnotherTorrentInItsFolder() {
        var row = DownloadRegressionTest.row(); row.bindHash("known");
        var engine = new DownloadRegressionTest.Engine();
        engine.snapshots = List.of(DownloadRegressionTest.snapshot("downloading", .2, "/downloads/incoming/test"));
        new QbittorrentDownloadSynchronizer(DownloadRegressionTest.repository(row), engine, null, new DownloadUpdatePublisher()).synchronize();
        assertEquals("known", row.infoHash()); assertEquals("ERROR", row.status());
    }
    @Test void explicitHashCannotBeOverriddenByAnEqualName() {
        var requested = "b".repeat(40);
        var engine = new DownloadRegressionTest.Engine() {
            @Override public void add(String ref, String path) { commands.add("add:" + ref); }
        };
        engine.snapshots = List.of(new QbittorrentClient.TorrentSnapshot("a".repeat(40), "downloading", .2,
                1000, 200, 0, 0, "/downloads/incoming/old", "test-release"));
        var saved = new ArrayList<DownloadRow>();
        var storage = new dev.qlcstream.library.LocalStorageSettings(null) {
            @Override public String downloadDirectory() { return "incoming"; }
        };
        new DownloadSubmissionService(DownloadRegressionTest.newRepository(saved), engine, storage).submitSeries(
                new DownloadSubmissionService.SeriesDownloadRequest(1, "Owned", null, 1, 1,
                        "test-release", "magnet:?xt=urn:btih:" + requested, null, null, null, null));
        assertNull(saved.getFirst().infoHash()); assertEquals(1, engine.commands.size());
        assertTrue(engine.commands.getFirst().contains(requested));
    }
    @Test void unknownUrlIdentityWithSameNameIsRejectedWithoutAdoptingOrAdding() {
        var engine = new DownloadRegressionTest.Engine();
        engine.snapshots = List.of(new QbittorrentClient.TorrentSnapshot("a".repeat(40), "downloading", .2,
                1000, 200, 0, 0, "/downloads/incoming/old", "test-release"));
        var saved = new ArrayList<DownloadRow>();
        assertThrows(IllegalStateException.class, () -> new DownloadSubmissionService(
                DownloadRegressionTest.newRepository(saved), engine, null).submitSeries(DownloadRegressionTest.newSeriesRequest()));
        assertTrue(saved.isEmpty()); assertTrue(engine.commands.isEmpty());
    }
    @Test void cancelForFileDeletionActivelyRetriesAlreadyCanceledTransferAndLateHash() {
        var row = DownloadRegressionTest.row();
        var engine = new DownloadRegressionTest.Engine();
        var repo = DownloadRegressionTest.repository(row);
        var service = new DownloadControlService(repo, engine);

        // Initial cancel before hash exists leaves durable CANCELED intent.
        service.control(row.id(), DownloadControlService.Action.CANCEL);
        assertEquals("CANCELED", row.status());
        assertFalse(service.cancelForFileDeletion(row.id()));

        // Late torrent materializes in the reserved folder: file-deletion retry resolves and removes it.
        engine.snapshots = List.of(DownloadRegressionTest.snapshot("seeding", 1.0, "/downloads/incoming/test"));
        assertFalse(service.cancelForFileDeletion(row.id()));
        assertEquals("hash", row.infoHash());
        assertEquals(List.of("remove:hash"), engine.commands);

        // Retrying while torrent is still in the engine (even with uppercase hex) actively re-sends remove.
        engine.snapshots = List.of(new QbittorrentClient.TorrentSnapshot("HASH", "seeding", 1.0, 1000, 1000, 0, 0, "/downloads/incoming/test"));
        assertFalse(service.cancelForFileDeletion(row.id()));
        assertEquals(List.of("remove:hash", "remove:hash"), engine.commands);

        // Once engine confirms absence, download transitions to REMOVED and authorizes byte deletion.
        engine.snapshots = List.of();
        assertTrue(service.cancelForFileDeletion(row.id()));
        assertEquals("REMOVED", row.status());
    }
    @Test void cancelForFileDeletionPreservesBoundHashIfVerificationFailsAfterRemoval() throws Exception {
        var transactional = DownloadControlService.class
                .getMethod("cancelForFileDeletion", UUID.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertNotNull(transactional);
        assertTrue(Arrays.asList(transactional.noRollbackFor())
                .contains(QbittorrentClient.QbittorrentUnavailableException.class));

        var row = DownloadRegressionTest.row();
        row.markStatus("CANCELED");
        var calls = new int[]{0};
        var engine = new DownloadRegressionTest.Engine() {
            @Override public List<TorrentSnapshot> torrents() {
                if (++calls[0] == 1) return List.of(DownloadRegressionTest.snapshot("seeding", 1.0, "/downloads/incoming/test"));
                return new QbittorrentClient(
                        new dev.qlcstream.downloads.config.DownloadQbittorrentProperties("", "", ""), null).torrents();
            }
        };
        var service = new DownloadControlService(DownloadRegressionTest.repository(row), engine);
        assertThrows(QbittorrentClient.QbittorrentUnavailableException.class,
                () -> service.cancelForFileDeletion(row.id()));
        assertEquals("hash", row.infoHash());
        assertEquals("CANCELED", row.status());
        assertEquals(List.of("remove:hash"), engine.commands);
    }
    @Test void cancelForFileDeletionBindsHashBeforeRemoveSoLostRemovalResponseCanRetry() throws Exception {
        var controlTx = DownloadControlService.class
                .getMethod("control", UUID.class, DownloadControlService.Action.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertNotNull(controlTx);
        assertTrue(Arrays.asList(controlTx.noRollbackFor())
                .contains(QbittorrentClient.QbittorrentUnavailableException.class));

        // Scenario 1: already CANCELED without hash; engine deletes torrent but loses the HTTP response.
        var canceledRow = DownloadRegressionTest.row();
        canceledRow.markStatus("CANCELED");
        var lostRemoveEngine = new DownloadRegressionTest.Engine() {
            @Override public void remove(String hash) {
                super.remove(hash);
                snapshots = List.of();
                new QbittorrentClient(
                        new dev.qlcstream.downloads.config.DownloadQbittorrentProperties("", "", ""), null).torrents();
            }
        };
        lostRemoveEngine.snapshots = List.of(DownloadRegressionTest.snapshot("seeding", 1.0, "/downloads/incoming/test"));
        var canceledService = new DownloadControlService(DownloadRegressionTest.repository(canceledRow), lostRemoveEngine);
        assertThrows(QbittorrentClient.QbittorrentUnavailableException.class,
                () -> canceledService.cancelForFileDeletion(canceledRow.id()));
        assertEquals("hash", canceledRow.infoHash());
        assertEquals("CANCELED", canceledRow.status());
        assertTrue(canceledService.cancelForFileDeletion(canceledRow.id()));
        assertEquals("REMOVED", canceledRow.status());

        // Scenario 2: active download without hash; initial CANCEL removes torrent and loses response.
        var activeRow = DownloadRegressionTest.row();
        var activeEngine = new DownloadRegressionTest.Engine() {
            @Override public void remove(String hash) {
                super.remove(hash);
                snapshots = List.of();
                new QbittorrentClient(
                        new dev.qlcstream.downloads.config.DownloadQbittorrentProperties("", "", ""), null).torrents();
            }
        };
        activeEngine.snapshots = List.of(DownloadRegressionTest.snapshot("seeding", 1.0, "/downloads/incoming/test"));
        var activeService = new DownloadControlService(DownloadRegressionTest.repository(activeRow), activeEngine);
        assertThrows(QbittorrentClient.QbittorrentUnavailableException.class,
                () -> activeService.cancelForFileDeletion(activeRow.id()));
        assertEquals("hash", activeRow.infoHash());
        assertEquals("CANCELED", activeRow.status());
        assertTrue(activeService.cancelForFileDeletion(activeRow.id()));
        assertEquals("REMOVED", activeRow.status());
    }
    @Test void synchronizerBindsHashBeforeRemoveSoLostRemovalResponseCompletesOnNextCycle() {
        var row = DownloadRegressionTest.row();
        row.markStatus("CANCELED");
        var engine = new DownloadRegressionTest.Engine() {
            @Override public void remove(String hash) {
                super.remove(hash);
                snapshots = List.of();
                new QbittorrentClient(
                        new dev.qlcstream.downloads.config.DownloadQbittorrentProperties("", "", ""), null).torrents();
            }
        };
        engine.snapshots = List.of(DownloadRegressionTest.snapshot("downloading", 0.5, "/downloads/incoming/test"));
        var synchronizer = new QbittorrentDownloadSynchronizer(
                DownloadRegressionTest.repository(row), engine, null, new DownloadUpdatePublisher());

        synchronizer.synchronize();
        assertEquals("hash", row.infoHash());
        assertEquals("CANCELED", row.status());

        synchronizer.synchronize();
        assertEquals("REMOVED", row.status());
    }
    @Test void synchronizerConfirmsTorrentAbsenceAfterLockBeforeMarkingRemoved() {
        var row = DownloadRegressionTest.row();
        var engine = new DownloadRegressionTest.Engine();
        // Pre-lock snapshot is empty; during the window before row locks are acquired,
        // the torrent appears in qBittorrent and a concurrent cancel persists CANCELED + hash even after remove fails.
        engine.snapshots = List.of();
        var baseRepo = DownloadRegressionTest.repository(row);
        var interleaved = new boolean[]{false};
        var repo = (SpringDataDownloadRepository) java.lang.reflect.Proxy.newProxyInstance(
                DownloadLifecycleTest.class.getClassLoader(),
                new Class<?>[]{SpringDataDownloadRepository.class},
                (proxy, method, args) -> {
                    if ("findByStatusNotIn".equals(method.getName()) && !interleaved[0]) {
                        interleaved[0] = true;
                        engine.snapshots = List.of(DownloadRegressionTest.snapshot("downloading", 0.5, "/downloads/incoming/test"));
                        row.bindHash("hash");
                        row.markStatus("CANCELED");
                    }
                    return method.invoke(baseRepo, args);
                });
        var synchronizer = new QbittorrentDownloadSynchronizer(repo, engine, null, new DownloadUpdatePublisher());

        synchronizer.synchronize();
        assertEquals("CANCELED", row.status(), "Stale pre-lock snapshot must never mark an active torrent REMOVED");
        assertEquals(List.of("remove:hash"), engine.commands);
        assertFalse(new DownloadControlService(repo, engine).cancelForFileDeletion(row.id()));

        engine.snapshots = List.of();
        synchronizer.synchronize();
        assertEquals("REMOVED", row.status());
    }
}
