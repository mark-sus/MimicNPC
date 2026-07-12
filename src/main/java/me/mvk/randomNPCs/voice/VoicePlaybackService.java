package me.mvk.randomNPCs.voice;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class VoicePlaybackService {

    // Фіксований розмір фрейму, який вимагає SVC API (20 мс при 48kHz)
    private static final int FRAME_SIZE = 960;

    private final VoiceCaptureManager captureManager;
    private final Logger logger;
    private final Plugin plugin;

    public VoicePlaybackService(VoiceCaptureManager captureManager, Plugin plugin) {
        this.captureManager = captureManager;
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    private void log(String message) {
        if (plugin.getConfig().getBoolean("voice.debug", true)) {
            logger.info("[Voice] " + message);
        }
    }

    public AudioPlayer playAt(Location location, short[] pcm) {
        if (!captureManager.isReady()) {
            log("playAt() скасовано: SVC API не готовий.");
            return null;
        }
        if (pcm == null || pcm.length == 0) {
            log("playAt() скасовано: немає PCM-даних для відтворення (порожній кліп).");
            return null;
        }

        VoicechatServerApi api = captureManager.getApi();
        World world = location.getWorld();
        if (world == null) {
            log("playAt() скасовано: у локації NPC немає світу (world == null).");
            return null;
        }

        LocationalAudioChannel channel = api.createLocationalAudioChannel(
                UUID.randomUUID(),
                api.fromServerLevel(world),
                api.createPosition(location.getX(), location.getY(), location.getZ())
        );
        if (channel == null) {
            // null означає, що жоден онлайн-гравець зараз не має встановленого SVC-клієнта
            log("playAt() скасовано: createLocationalAudioChannel повернув null "
                    + "(ймовірно, жоден гравець поблизу не має встановленого клієнта Simple Voice Chat).");
            return null;
        }
        channel.setDistance((float) plugin.getConfig().getDouble("voice.playback-distance", 24.0));

        AtomicInteger cursor = new AtomicInteger(0);
        AtomicInteger framesServed = new AtomicInteger(0);
        AudioPlayer player = api.createAudioPlayer(channel, api.createEncoder(), () -> {
            int pos = cursor.getAndAdd(FRAME_SIZE);
            if (pos >= pcm.length) {
                return null; // сигнал кінця відтворення для AudioPlayer
            }
            int served = framesServed.incrementAndGet();
            if (served == 1 || served % 50 == 0) {
                log("Передано фрейм #" + served + " (SVC реально запитує аудіо у supplier-а).");
            }
            short[] frame = new short[FRAME_SIZE];
            int available = Math.min(FRAME_SIZE, pcm.length - pos);
            System.arraycopy(pcm, pos, frame, 0, available);
            return frame;
        });
        player.setOnStopped(() -> log("Відтворення завершено. Всього передано фреймів: " + framesServed.get()
                + " з очікуваних ~" + (pcm.length / FRAME_SIZE) + "."));
        player.startPlaying();
        log("Відтворення запущено: " + pcm.length + " семплів (~"
                + String.format("%.1f", pcm.length / 48000.0) + " сек) на позиції "
                + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ()
                + ", channel.getDistance()=" + channel.getDistance() + ".");
        return player;
    }
}