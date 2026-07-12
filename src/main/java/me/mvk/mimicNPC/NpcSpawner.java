package me.mvk.mimicNPC;

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
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class NpcSpawner {

    private final MimicNPCPlugin plugin;
    private final Random random = new Random();

    public NpcSpawner(MimicNPCPlugin plugin) {
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

        boolean caveMode = plugin.getConfig().getBoolean("spawn-in-caves", true);
        Location spawnLoc = caveMode ? findCaveLocationNear(targetPlayer.getLocation()) : null;
        if (spawnLoc == null) {
            // Печеру не знайдено (або spawn-in-caves=false) - як і раніше, шукаємо місце на поверхні
            spawnLoc = findSafeLocationNear(targetPlayer.getLocation());
        }
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

        // NPC "носить" не лише скін гравця, а й копію його нинішнього інвентаря на момент спавну
        // (знімок один раз при спавні - подальші зміни в реальному інвентарі гравця вже не впливають на NPC)
        copyInventory(skinOwner, npc);

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

    // Шукає місце для спавну під землею - в "печері" (повітряна кишеня зі стелею з каменю
    // над головою, а не просто яма без даху). Використовує ту саму горизонтальну відстань
    // від гравця (spawn-radius-min/max), що й пошук на поверхні, але шукає по висоті
    // окремо в діапазоні cave-min-y..cave-max-y.
    private Location findCaveLocationNear(Location center) {
        int minR = plugin.getConfig().getInt("spawn-radius-min", 5);
        int maxR = plugin.getConfig().getInt("spawn-radius-max", 12);
        World world = center.getWorld();
        if (world == null) return null;

        int minY = Math.max(world.getMinHeight() + 4, plugin.getConfig().getInt("cave-min-y", -40));
        int maxY = Math.min(world.getSeaLevel(), plugin.getConfig().getInt("cave-max-y", 45));
        if (maxY <= minY) return null;

        for (int attempt = 0; attempt < 30; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = minR + random.nextDouble() * (maxR - minR);
            int dx = (int) Math.round(Math.cos(angle) * dist);
            int dz = (int) Math.round(Math.sin(angle) * dist);
            int x = center.getBlockX() + dx;
            int z = center.getBlockZ() + dz;

            int surfaceY = world.getHighestBlockYAt(x, z);
            // Стеля з каменю має бути щонайменше 4 блоки завтовшки над кишенею - інакше
            // це просто яма на поверхні, а не справжня печера
            int columnTop = Math.min(maxY, surfaceY - 4);
            if (columnTop <= minY) continue;

            int y = minY + random.nextInt(columnTop - minY + 1);

            Block ground = world.getBlockAt(x, y, z);
            Block above = world.getBlockAt(x, y + 1, z);
            Block above2 = world.getBlockAt(x, y + 2, z);

            if (ground.getType().isSolid() && above.getType().isAir() && above2.getType().isAir()
                    && y < surfaceY - 3) {
                return new Location(world, x + 0.5, y + 1, z + 0.5);
            }
        }
        return null;
    }

    // Робить знімок інвентаря гравця, чий скін носить NPC, і переносить його на NPC-сутність
    // (основний інвентар, броня, друга рука). Це саме КОПІЯ - оригінальний ItemStack клонується,
    // тож подальші зміни в реальному інвентарі гравця NPC вже не зачіпають.
    private void copyInventory(Player source, NPC npc) {
        if (!(npc.getEntity() instanceof Player npcPlayer)) {
            return;
        }

        ItemStack[] contents = source.getInventory().getContents();
        ItemStack[] clonedContents = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            clonedContents[i] = contents[i] == null ? null : contents[i].clone();
        }
        npcPlayer.getInventory().setContents(clonedContents);

        ItemStack[] armor = source.getInventory().getArmorContents();
        ItemStack[] clonedArmor = new ItemStack[armor.length];
        for (int i = 0; i < armor.length; i++) {
            clonedArmor[i] = armor[i] == null ? null : armor[i].clone();
        }
        npcPlayer.getInventory().setArmorContents(clonedArmor);

        ItemStack offhand = source.getInventory().getItemInOffHand();
        npcPlayer.getInventory().setItemInOffHand(offhand.clone());

        npcPlayer.updateInventory();
    }

    private String formatLoc(Location loc) {
        return "(" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")";
    }
}