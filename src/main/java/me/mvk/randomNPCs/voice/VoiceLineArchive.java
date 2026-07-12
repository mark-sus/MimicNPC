package me.mvk.randomNPCs.voice;

import me.mvk.randomNPCs.RandomNPCPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class VoiceLineArchive {

    private static final int SAMPLE_RATE = 48000;

    private final RandomNPCPlugin plugin;
    private final AtomicInteger savedThisSession = new AtomicInteger(0);

    public VoiceLineArchive(RandomNPCPlugin plugin) {
        this.plugin = plugin;
    }

    private File archiveDir() {
        File dir = new File(plugin.getDataFolder(), "voicelines");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("[Voice] Не вдалося створити папку архіву voicelines: " + dir.getPath());
        }
        return dir;
    }

    private boolean debug() {
        return plugin.getConfig().getBoolean("voice.debug", true);
    }

    private void log(String message) {
        if (debug()) {
            plugin.getLogger().log(Level.INFO, "[Voice] " + message);
        }
    }

    /**
     * З заданим у конфізі шансом (voice.archive-chance) зберігає кліп на диск НАЗАВЖДИ,
     * незалежно від того, що звичайний буфер в оперативній пам'яті все одно затреться.
     * Не впливає на саме відтворення - викликається як додатковий побічний ефект.
     */
    public void maybeArchive(UUID skinOwnerId, String skinOwnerName, short[] pcm) {
        if (!plugin.getConfig().getBoolean("voice.archive-enabled", true)) {
            return;
        }
        if (pcm == null || pcm.length == 0) {
            return;
        }

        double chance = plugin.getConfig().getDouble("voice.archive-chance", 0.2);
        if (Math.random() > chance) {
            log("Архівування кліпу гравця " + safeName(skinOwnerName)
                    + " пропущено (не спрацював шанс архівації=" + chance + ").");
            return;
        }

        File dir = archiveDir();
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        String fileName = timestamp + "_" + safeName(skinOwnerName) + "_" + shortUuid(skinOwnerId) + ".wav";
        File out = new File(dir, fileName);

        try {
            writeWav(out, pcm);
            int total = savedThisSession.incrementAndGet();
            log("Кліп голосу гравця " + safeName(skinOwnerName) + " збережено НАЗАВЖДИ: " + out.getName()
                    + " (" + pcm.length + " семплів, ~" + String.format("%.1f", pcm.length / (double) SAMPLE_RATE)
                    + " сек). Збережено файлів за цю сесію: " + total
                    + ". Очистити все: /randomnpc clearvoicelines.");
        } catch (IOException e) {
            plugin.getLogger().warning("[Voice] Не вдалося зберегти voiceline у файл " + out.getPath()
                    + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Видаляє всі збережені voiceline-файли з диску. Повертає кількість реально видалених файлів. */
    public int clearAll() {
        File dir = archiveDir();
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".wav"));
        if (files == null || files.length == 0) {
            return 0;
        }
        int deleted = 0;
        for (File f : files) {
            if (f.delete()) {
                deleted++;
            } else {
                plugin.getLogger().warning("[Voice] Не вдалося видалити файл voiceline: " + f.getPath());
            }
        }
        savedThisSession.set(0);
        log("Очищено архів voicelines: видалено " + deleted + " з " + files.length + " файлів.");
        return deleted;
    }

    /** Скільки voiceline-файлів наразі лежить на диску. */
    public int count() {
        File dir = archiveDir();
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".wav"));
        return files == null ? 0 : files.length;
    }

    private String safeName(String name) {
        if (name == null || name.isEmpty()) {
            return "unknown";
        }
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private String shortUuid(UUID id) {
        return id == null ? "00000000" : id.toString().substring(0, 8);
    }

    // Пише звичайний 16-бітний PCM моно WAV-файл (44-байтний заголовок + дані),
    // який відкриється будь-яким плеєром без додаткових бібліотек.
    private void writeWav(File file, short[] pcm) throws IOException {
        int dataLength = pcm.length * 2;
        int byteRate = SAMPLE_RATE * 2;

        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes());
        header.putInt(36 + dataLength);
        header.put("WAVE".getBytes());
        header.put("fmt ".getBytes());
        header.putInt(16);          // розмір fmt-чанка для PCM
        header.putShort((short) 1); // формат аудіо = PCM
        header.putShort((short) 1); // каналів = 1 (моно)
        header.putInt(SAMPLE_RATE);
        header.putInt(byteRate);
        header.putShort((short) 2);  // block align (16 біт * 1 канал / 8)
        header.putShort((short) 16); // біт на семпл
        header.put("data".getBytes());
        header.putInt(dataLength);

        ByteBuffer body = ByteBuffer.allocate(dataLength).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : pcm) {
            body.putShort(s);
        }

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(header.array());
            fos.write(body.array());
        }
    }
}