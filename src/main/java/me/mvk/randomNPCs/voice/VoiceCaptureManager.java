package me.mvk.randomNPCs.voice;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import me.mvk.randomNPCs.RandomNPCPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class VoiceCaptureManager {

    private static final int SAMPLE_RATE = 48000;

    private final Random random = new Random();

    private final RandomNPCPlugin plugin;
    private volatile VoicechatServerApi api;

    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();
    private final Map<UUID, PcmRingBuffer> buffers = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> refCounts = new ConcurrentHashMap<>();
    // Лічильник пакетів на гравця - лише щоб не спамити лог на кожен пакет мікрофона
    private final Map<UUID, AtomicInteger> packetCounters = new ConcurrentHashMap<>();

    public VoiceCaptureManager(RandomNPCPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean debug() {
        return plugin.getConfig().getBoolean("voice.debug", true);
    }

    private void log(String message) {
        if (debug()) {
            plugin.getLogger().log(Level.INFO, "[Voice] " + message);
        }
    }

    private String nameOf(UUID playerId) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(playerId);
        String name = op.getName();
        return name != null ? name : playerId.toString();
    }

    public void setApi(VoicechatServerApi api) {
        this.api = api;
        plugin.getLogger().info("[Voice] SVC API отримано, voicechat-api ініціалізовано успішно.");
    }

    public VoicechatServerApi getApi() {
        return api;
    }

    public boolean isReady() {
        return api != null;
    }

    public void track(UUID playerId) {
        if (!isReady()) {
            plugin.getLogger().warning("[Voice] Спроба трекінгу гравця " + nameOf(playerId)
                    + " відхилена: SVC API ще не готовий (плагін voicechat не встановлено/не увімкнено, "
                    + "або SVC ще не встиг ініціалізуватись).");
            return;
        }
        if (!plugin.getConfig().getBoolean("voice.enabled", true)) {
            log("Трекінг " + nameOf(playerId) + " пропущено: voice.enabled=false в config.yml.");
            return;
        }
        int newCount = refCounts.merge(playerId, 1, Integer::sum);
        buffers.computeIfAbsent(playerId, id -> {
            int seconds = plugin.getConfig().getInt("voice.buffer-seconds", 12);
            return new PcmRingBuffer(SAMPLE_RATE * seconds);
        });
        decoders.computeIfAbsent(playerId, id -> api.createDecoder());
        packetCounters.computeIfAbsent(playerId, id -> new AtomicInteger(0));
        log("Почато відстеження " + nameOf(playerId) + " (активних посилань: " + newCount + ").");
    }

    public void untrack(UUID playerId) {
        Integer remaining = refCounts.merge(playerId, -1, Integer::sum);
        log("Відстеження " + nameOf(playerId) + " -1 (залишилось посилань: " + remaining + ").");
        if (remaining == null || remaining <= 0) {
            refCounts.remove(playerId);
            buffers.remove(playerId);
            packetCounters.remove(playerId);
            OpusDecoder decoder = decoders.remove(playerId);
            if (decoder != null) {
                decoder.close();
            }
            log("Повністю звільнено ресурси голосу для " + nameOf(playerId) + ".");
        }
    }

    public boolean isTracked(UUID playerId) {
        return buffers.containsKey(playerId);
    }

    public void onMicPacket(UUID playerId, byte[] opusData) {
        PcmRingBuffer buffer = buffers.get(playerId);
        OpusDecoder decoder = decoders.get(playerId);
        if (buffer == null || decoder == null) return;

        short[] pcm = decoder.decode(opusData == null || opusData.length == 0 ? null : opusData);
        if (pcm != null) {
            buffer.write(pcm);
            AtomicInteger counter = packetCounters.get(playerId);
            if (counter != null) {
                int count = counter.incrementAndGet();
                // Лог кожного 1-го (підтвердження що взагалі щось приходить) і далі кожного 100-го пакета
                if (count == 1 || count % 100 == 0) {
                    log("Отримано пакет мікрофона #" + count + " від " + nameOf(playerId)
                            + " (" + pcm.length + " семплів у пакеті).");
                }
            }
        }
    }

    public short[] takeSnapshot(UUID playerId) {
        PcmRingBuffer buffer = buffers.get(playerId);
        short[] full = buffer == null ? null : buffer.snapshot();
        if (full == null || full.length == 0) {
            log("Знімок буфера для " + nameOf(playerId) + ": null (нічого не записано).");
            return null;
        }

        int minSeconds = plugin.getConfig().getInt("voice.clip-min-seconds", 5);
        int maxSeconds = plugin.getConfig().getInt("voice.clip-max-seconds", 8);
        if (maxSeconds < minSeconds) maxSeconds = minSeconds;
        int targetSeconds = minSeconds + (maxSeconds > minSeconds ? random.nextInt(maxSeconds - minSeconds + 1) : 0);
        int targetLength = SAMPLE_RATE * targetSeconds;

        int silenceThreshold = plugin.getConfig().getInt("voice.silence-rms-threshold", 300);
        short[] clip = VoiceActivitySelector.extractLoudestSegment(full, targetLength, silenceThreshold);

        if (clip == null) {
            log("Знімок буфера для " + nameOf(playerId) + ": в накопичених " + full.length
                    + " семплах не знайдено достатньо голосної ділянки довжиною ~" + targetSeconds
                    + " сек (схоже, гравець переважно мовчав) - відтворення пропущено.");
            return null;
        }

        log("Знімок буфера для " + nameOf(playerId) + ": обрано голосний відрізок " + clip.length
                + " семплів (~" + targetSeconds + " сек) із загального буфера " + full.length + " семплів.");
        return clip;
    }

    public String describeStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("SVC API готовий: ").append(isReady()).append("\n");
        sb.append("voice.enabled: ").append(plugin.getConfig().getBoolean("voice.enabled", true)).append("\n");
        sb.append("Відстежується гравців: ").append(buffers.size());
        for (UUID id : buffers.keySet()) {
            int packets = packetCounters.containsKey(id) ? packetCounters.get(id).get() : 0;
            sb.append("\n  - ").append(nameOf(id))
                    .append(": пакетів отримано=").append(packets)
                    .append(", посилань=").append(refCounts.getOrDefault(id, 0));
        }
        return sb.toString();
    }
}