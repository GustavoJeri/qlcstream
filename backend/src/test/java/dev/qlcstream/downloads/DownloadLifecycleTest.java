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
}
