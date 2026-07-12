package me.mvk.randomNPCs;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.SkinTrait;
import net.citizensnpcs.trait.LookClose;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class NpcSpawner {

    private final RandomNPCPlugin plugin;
    private final Random random = new Random();

    public NpcSpawner(RandomNPCPlugin plugin) {
        this.plugin = plugin;
    }

    public void trySpawnNpc() {
        int maxActive = plugin.getConfig().getInt("max-active-npcs", 5);
        if (plugin.getOurNpcIds().size() >= maxActive) {
            return;
        }

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) {
            return;
        }
        Player targetPlayer = online.get(random.nextInt(online.size()));

        Player skinOwner = pickRandomOnlinePlayer(online, targetPlayer);
        if (skinOwner == null || skinOwner.getName() == null) {
            plugin.getLogger().warning("Не вдалося знайти онлайн-гравця для скіна NPC.");
            return;
        }

        Location spawnLoc = findSafeLocationNear(targetPlayer.getLocation());
        if (spawnLoc == null) {
            return;
        }

        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        NPC npc = registry.createNPC(EntityType.PLAYER, skinOwner.getName());

        // Встановлюємо скін гравця, який зараз онлайн
        npc.getOrAddTrait(SkinTrait.class).setSkinName(skinOwner.getName(), true);

        // Ховаємо нік (нейметег) над головою NPC та прибираємо з таб-листа.
        // ВАЖЛИВО: ці налаштування мають бути виставлені ДО spawn(),
        // інакше Citizens не встигає застосувати приховування через scoreboard.
        npc.data().setPersistent(net.citizensnpcs.api.npc.NPC.Metadata.NAMEPLATE_VISIBLE, false);
        npc.data().setPersistent(net.citizensnpcs.api.npc.NPC.Metadata.REMOVE_FROM_PLAYERLIST, true);

        // Природно повертає голову й дивиться на гравців поруч - виглядає більш "живо"
        npc.getOrAddTrait(LookClose.class).lookClose(true);

        npc.spawn(spawnLoc);
        npc.setProtected(false);
        npc.data().setPersistent("randomnpc-owned", true);

        // Трохи випадкової швидкості (як у різних гравців різний "темп ходьби")
        double speed = 0.9 + random.nextDouble() * 0.3; // 0.9x - 1.2x від базової
        npc.getNavigator().getDefaultParameters().speedModifier((float) speed);
        // Дозволяємо відкривати двері та обходити воду - природніша навігація
        npc.getNavigator().getDefaultParameters().avoidWater(true);

        plugin.getOurNpcIds().add(npc.getId());

        // Починаємо захоплювати голос гравця, чий скін зараз "носить" цей NPC -
        // саме його спотворений голос буде використано для ефекту при розкритті
        plugin.getVoiceCaptureManager().track(skinOwner.getUniqueId());

        new NpcBehaviorTask(plugin, npc, skinOwner.getUniqueId()).start();

        plugin.getLogger().info("Заспавнено NPC зі скіном '" + skinOwner.getName()
                + "' біля гравця " + targetPlayer.getName() + " у " + formatLoc(spawnLoc));
    }

    // Вибирає випадкового гравця з тих, хто зараз онлайн.
    // Якщо онлайн більше одного гравця, намагається уникнути того ж гравця,
    // біля якого спавниться NPC (щоб не виглядало як "клон самого себе").
    private Player pickRandomOnlinePlayer(List<Player> online, Player avoid) {
        if (online.isEmpty()) {
            return null;
        }
        if (online.size() == 1) {
            return online.get(0);
        }
        List<Player> candidates = new ArrayList<>(online);
        candidates.remove(avoid);
        if (candidates.isEmpty()) {
            candidates = online;
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    // Шукає безпечне місце (тверда земля, повітря над нею) у кільці навколо гравця
    private Location findSafeLocationNear(Location center) {
        int minR = plugin.getConfig().getInt("spawn-radius-min", 5);
        int maxR = plugin.getConfig().getInt("spawn-radius-max", 12);
        World world = center.getWorld();
        if (world == null) return null;

        for (int attempt = 0; attempt < 20; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = minR + random.nextDouble() * (maxR - minR);
            int dx = (int) Math.round(Math.cos(angle) * dist);
            int dz = (int) Math.round(Math.sin(angle) * dist);

            int x = center.getBlockX() + dx;
            int z = center.getBlockZ() + dz;
            int y = world.getHighestBlockYAt(x, z);

            Block ground = world.getBlockAt(x, y, z);
            Block above = world.getBlockAt(x, y + 1, z);
            Block above2 = world.getBlockAt(x, y + 2, z);

            if (ground.getType().isSolid() && above.getType().isAir() && above2.getType().isAir()) {
                return new Location(world, x + 0.5, y + 1, z + 0.5);
            }
        }
        return null;
    }

    private String formatLoc(Location loc) {
        return "(" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")";
    }
}