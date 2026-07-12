package me.mvk.mimicNPC;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import me.mvk.mimicNPC.voice.NpcVoicechatPlugin;
import me.mvk.mimicNPC.voice.VoiceCaptureManager;
import me.mvk.mimicNPC.voice.VoiceLineArchive;
import me.mvk.mimicNPC.voice.VoicePlaybackService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MimicNPCPlugin extends JavaPlugin {

    // Реєстр усіх NPC, створених саме цим плагіном (щоб не чіпати чужих Citizens NPC)
    private final Set<Integer> ourNpcIds = new HashSet<>();
    // Активні NpcBehaviorTask по id NPC - потрібно, щоб NpcLootListener міг знайти,
    // якому саме NPC зарахувати дроп з вбитого моба (замість того, щоб дроп падав на землю одразу)
    private final Map<Integer, NpcBehaviorTask> activeTasks = new ConcurrentHashMap<>();
    private NpcSpawner spawner;

    // Голосова механіка (працює лише якщо на сервері встановлено Simple Voice Chat)
    private final VoiceCaptureManager voiceCaptureManager = new VoiceCaptureManager(this);
    private final VoicePlaybackService voicePlaybackService = new VoicePlaybackService(voiceCaptureManager, this);
    // Постійний архів деяких голосових кліпів на диску - живе окремо від voiceCaptureManager
    // і не зникає при рестарті сервера, доки його не очистять командою /randomnpc clearvoicelines
    private final VoiceLineArchive voiceLineArchive = new VoiceLineArchive(this);

    public VoiceCaptureManager getVoiceCaptureManager() {
        return voiceCaptureManager;
    }

    public VoicePlaybackService getVoicePlaybackService() {
        return voicePlaybackService;
    }

    public VoiceLineArchive getVoiceLineArchive() {
        return voiceLineArchive;
    }

    // Реєструє наш VoicechatPlugin у SVC. На Bukkit/Spigot/Paper це робиться
    // ЯВНО через ServicesManager - NpcVoicechatPlugin.initialize() не викликається сам собою.
    private void registerVoicechatPlugin() {
        BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            getLogger().warning("[Voice] BukkitVoicechatService недоступний - плагін voicechat "
                    + "не встановлено, вимкнено, або ще не зареєстрував свій сервіс на момент нашого onEnable().");
            return;
        }
        service.registerPlugin(new NpcVoicechatPlugin(this));
        getLogger().info("[Voice] Успішно зареєстровано в Simple Voice Chat через BukkitVoicechatService.");
    }

    // Citizens зберігає своїх NPC на диск і сам відновлює їх при старті сервера -
    // якщо сервер вимкнувся не через штатний onDisable() (креш, kill -9, /stop без плагіна
    // тощо), наш ourNpcIds (звичайний Set у пам'яті) обнуляється, а самі NPC на диску
    // Citizens лишаються і заново спавняться при наступному onEnable(). Оскільки жоден
    // NpcBehaviorTask для них більше не запускається (ourNpcIds порожній на старті),
    // такі NPC назавжди залишаються "без ШІ" - не рухаються, не деспавняться при
    // наближенні гравця. Тому при кожному onEnable() прибираємо всіх NPC, позначених
    // персистентним тегом "randomnpc-owned" (він виставляється в NpcSpawner при спавні) -
    // це гарантує чистий старт: жодного "привида" від попередньої сесії.
    private void cleanupOrphanedNpcs() {
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        List<NPC> toRemove = new ArrayList<>();
        for (NPC npc : registry) {
            if (Boolean.TRUE.equals(npc.data().get("randomnpc-owned"))) {
                toRemove.add(npc);
            }
        }
        for (NPC npc : toRemove) {
            npc.destroy();
        }
        if (!toRemove.isEmpty()) {
            getLogger().info("Видалено " + toRemove.size() + " «завислих» NPC без ШІ, "
                    + "що лишились від попереднього запуску сервера.");
        }
    }

    @Override
    public void onEnable() {
        if (getServer().getPluginManager().getPlugin("Citizens") == null) {
            getLogger().severe("Плагін Citizens не знайдено! RandomNPC вимикається.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        cleanupOrphanedNpcs();

        saveDefaultConfig();

        getServer().getPluginManager().registerEvents(new NpcLootListener(this), this);

        registerVoicechatPlugin();

        this.spawner = new NpcSpawner(this);

        long intervalSeconds = getConfig().getLong("spawn-interval-seconds", 300);
        long intervalTicks = intervalSeconds * 20L;

        // Перший спавн через 5 сек після старту, далі — за інтервалом з конфігу
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                spawner.trySpawnNpc();
            } catch (Throwable t) {
                String where = t.getStackTrace().length > 0 ? t.getStackTrace()[0].toString() : "н/д";
                getLogger().severe("Помилка при автоматичному спавні NPC: " + t.getClass().getName()
                        + ": " + t.getMessage() + " | at " + where);
            }
        }, 100L, intervalTicks);

        getLogger().info("RandomNPC увімкнено. Інтервал спавну: " + intervalSeconds + " сек.");
    }

    @Override
    public void onDisable() {
        // При вимкненні плагіна прибираємо всіх наших NPC, щоб вони не залишались "мертвим" сміттям
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        for (Integer id : new HashSet<>(ourNpcIds)) {
            NPC npc = registry.getById(id);
            if (npc != null) {
                npc.destroy();
            }
        }
        ourNpcIds.clear();
        activeTasks.clear();
    }

    public Set<Integer> getOurNpcIds() {
        return ourNpcIds;
    }

    public void registerTask(int npcId, NpcBehaviorTask task) {
        activeTasks.put(npcId, task);
    }

    public void unregisterTask(int npcId) {
        activeTasks.remove(npcId);
    }

    public NpcBehaviorTask getTaskForNpc(int npcId) {
        return activeTasks.get(npcId);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("randomnpc.admin")) {
            sender.sendMessage("§cУ вас немає прав на цю команду.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§eВикористання: /randomnpc <spawn|clear|reload|voicestatus|clearvoicelines>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "spawn" -> {
                try {
                    spawner.trySpawnNpc();
                    sender.sendMessage("§aСпроба заспавнити NPC виконана (дивіться консоль/лог, якщо не з'явився).");
                } catch (Throwable t) {
                    String where = t.getStackTrace().length > 0 ? t.getStackTrace()[0].toString() : "н/д";
                    getLogger().severe("Помилка при /randomnpc spawn: " + t.getClass().getName()
                            + ": " + t.getMessage() + " | at " + where);
                    sender.sendMessage("§cПомилка: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
            case "clear" -> {
                NPCRegistry registry = CitizensAPI.getNPCRegistry();
                int count = 0;
                for (Integer id : new HashSet<>(ourNpcIds)) {
                    NPC npc = registry.getById(id);
                    if (npc != null) {
                        npc.destroy();
                        count++;
                    }
                }
                ourNpcIds.clear();
                sender.sendMessage("§aВидалено NPC: " + count);
            }
            case "reload" -> {
                reloadConfig();
                sender.sendMessage("§aКонфіг перезавантажено.");
            }
            case "voicestatus" -> {
                String status = voiceCaptureManager.describeStatus();
                sender.sendMessage("§b--- Стан голосової механіки ---");
                for (String line : status.split("\n")) {
                    sender.sendMessage("§7" + line);
                }
                sender.sendMessage("§7Збережених назавжди voiceline-файлів на диску: " + voiceLineArchive.count());
            }
            case "clearvoicelines" -> {
                int deleted = voiceLineArchive.clearAll();
                sender.sendMessage("§aОчищено збережені voiceline-файли: " + deleted + ".");
            }
            default -> sender.sendMessage("§eВикористання: /randomnpc <spawn|clear|reload|voicestatus|clearvoicelines>");
        }
        return true;
    }
}