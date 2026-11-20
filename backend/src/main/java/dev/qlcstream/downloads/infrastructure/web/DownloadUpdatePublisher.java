package dev.qlcstream.downloads.infrastructure.web;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class DownloadUpdatePublisher {
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        var emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(ignored -> emitters.remove(emitter));
        emitters.add(emitter);
        try {
            emitter.send(SseEmitter.event().name("connected").data("ready"));
        } catch (IOException | IllegalStateException exception) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public void publish() {
        for (var emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("downloads-changed").data("updated"));
            } catch (IOException | IllegalStateException exception) {
                emitters.remove(emitter);
            }
        }
    }

    public void publishNotification(String type, String title, String message) {
        var notification = new DownloadNotification(type, title, message);
        for (var emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("download-notification").data(notification));
            } catch (IOException | IllegalStateException exception) {
                emitters.remove(emitter);
            }
        }
    }

    public record DownloadNotification(String type, String title, String message) {
    }
}
