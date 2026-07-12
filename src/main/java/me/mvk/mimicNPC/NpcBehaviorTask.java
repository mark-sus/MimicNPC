package me.mvk.mimicNPC;

import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.util.PlayerAnimation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import me.mvk.mimicNPC.voice.VoiceDistortion;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Кожні пів секунди перевіряє оточення NPC і вирішує, що робити:
 * 1) якщо поруч гравець - зникнути з димом
 * 2) якщо поруч дерево - піти до нього і "зрубати"
 * 3) якщо поруч мирний моб - піти до нього і вдарити
 * 4) інакше - випадково блукати
 * Паралельно (якщо NPC "балакучий" - див. voice.scare-chance) поки NPC живий,
 * він періодично запам'ятовує нові репліки голосу гравця і з інтервалом
 * voice.playback-gap-min/max-seconds відтворює випадкову із запам'ятованих.
 * Звук примусово зупиняється в момент зникнення NPC.
 */
public class NpcBehaviorTask extends BukkitRunnable {

    private final MimicNPCPlugin plugin;
    private final NPC npc;
    private final Random random = new Random();

    private enum State { IDLE, MOVING_TO_TREE, MINING, MOVING_TO_ORE, MINING_ORE, MOVING_TO_MOB, ATTACKING }

    // Інструмент, який використовується лише для розрахунку дропу з руди (не показується
    // в руці NPC) - беремо незачаровану незламну кирку, щоб NPC завжди міг "видобути"
    // будь-яку руду незалежно від її рівня твердості (аж до Ancient Debris)
    private static final ItemStack ORE_MINING_TOOL = new ItemStack(Material.NETHERITE_PICKAXE);

    private State state = State.IDLE;
    private Block targetTree;
    private Block targetOre;
    private LivingEntity targetMob;
    private int actionProgressTicks = 0;
    private int hitsLanded = 0;
    private int aliveTicks = 0;

    // Все, що NPC "видобув" (руда, дерево) або "забрав" з убитого моба за час свого життя.
    // Не потрапляє в реальний інвентар NPC і не падає на землю одразу - лежить тут,
    // доки NPC не зникне (наближення гравця або кінець часу життя), і тоді висипається
    // одним разом на землю в його останній локації.
    private final List<ItemStack> collectedDrops = new ArrayList<>();

    // UUID гравця, чий скін носить цей NPC - потрібен, щоб дістати його голосовий буфер
    private final UUID skinOwnerId;

    // Чи "балакучий" цей конкретний NPC - визначається один раз при спавні через voice.scare-chance,
    // щоб не кожен NPC постійно щось бубонів (для варіативності)
    private final boolean voiceEnabledForThisNpc;

    // Репліки голосу гравця, які NPC вже "запам'ятав" за час свого життя (вже спотворені,
    // готові до відтворення). Поповнюється кожні voice.memorize-interval-seconds секунд,
    // поки скін-власник щось каже в мікрофон поблизу NPC.
    private final List<short[]> memorizedVoiceLines = new ArrayList<>();

    // Плеєр поточного відтворення - потрібен, щоб примусово зупинити звук,
    // коли NPC зникає (інакше SVC дограє кліп навіть після знищення NPC).
    private AudioPlayer currentVoicePlayer;

    private int ticksUntilNextMemorize = 0;
    // -1 = ще не заплановано першого відтворення (чекаємо, поки з'явиться хоч одна репліка)
    private int ticksUntilNextPlayback = -1;

    public NpcBehaviorTask(MimicNPCPlugin plugin, NPC npc, UUID skinOwnerId) {
        this.plugin = plugin;
        this.npc = npc;
        this.skinOwnerId = skinOwnerId;
        double chance = plugin.getConfig().getDouble("voice.scare-chance", 0.35);
        this.voiceEnabledForThisNpc = skinOwnerId != null && random.nextDouble() <= chance;
    }

    public void start() {
        plugin.registerTask(npc.getId(), this);
        // Тік кожні 10 ігрових тіків (0.5 сек) — достатньо плавно і не навантажує сервер
        this.runTaskTimer(plugin, 20L, 10L);
    }

    // Викликається NpcLootListener-ом, коли цей NPC вбиває моба - забирає дроп
    // "у кишеню" NPC замість того, щоб він одразу впав на землю.
    public void collectDrops(Collection<ItemStack> drops) {
        if (drops == null) return;
        for (ItemStack item : drops) {
            if (item != null && item.getType() != Material.AIR) {
                collectedDrops.add(item.clone());
            }
        }
    }

    @Override
    public void run() {
        try {
            tick();
        } catch (Throwable t) {
            String where = t.getStackTrace().length > 0 ? t.getStackTrace()[0].toString() : "н/д";
            plugin.getLogger().severe("Помилка в поведінці NPC #" + npc.getId() + ": " + t.getClass().getName()
                    + ": " + t.getMessage() + " | at " + where);
            stopVoicePlayback();
            cancel();
            plugin.getOurNpcIds().remove(npc.getId());
            plugin.unregisterTask(npc.getId());
            if (skinOwnerId != null) {
                plugin.getVoiceCaptureManager().untrack(skinOwnerId);
            }
        }
    }

    private void tick() {
        if (!npc.isSpawned() || npc.getEntity() == null) {
            stopVoicePlayback();
            cancel();
            plugin.getOurNpcIds().remove(npc.getId());
            plugin.unregisterTask(npc.getId());
            if (skinOwnerId != null) {
                plugin.getVoiceCaptureManager().untrack(skinOwnerId);
            }
            return;
        }

        aliveTicks += 10;
        int maxLifetime = plugin.getConfig().getInt("max-lifetime-seconds", 900) * 20;
        if (maxLifetime > 0 && aliveTicks >= maxLifetime) {
            despawnWithSmoke();
            return;
        }

        Entity entity = npc.getEntity();
        Location loc = entity.getLocation();
        World world = loc.getWorld();
        if (world == null) return;

        // 1. Перевірка на близького гравця -> зникнення
        double despawnDist = plugin.getConfig().getDouble("despawn-distance", 3.0);
        Player nearestPlayer = findNearestPlayer(loc, despawnDist);
        if (nearestPlayer != null) {
            despawnWithSmoke();
            return;
        }

        tickVoiceMemory();
        tickVoicePlayback();

        updateSprinting();

        switch (state) {
            case MINING -> tickMining();
            case MOVING_TO_ORE -> tickMovingToOre();
            case MINING_ORE -> tickMiningOre();
            case ATTACKING -> tickAttacking();
            case MOVING_TO_TREE -> tickMovingToTree();
            case MOVING_TO_MOB -> tickMovingToMob();
            case IDLE -> tickIdle(loc, world);
        }
    }

    // Реальні гравці зазвичай біжать під час пересування і йдуть пішки під час дії
    private void updateSprinting() {
        if (!(npc.getEntity() instanceof Player npcPlayer)) return;
        boolean shouldSprint = state == State.MOVING_TO_TREE || state == State.MOVING_TO_MOB
                || state == State.MOVING_TO_ORE;
        if (npcPlayer.isSprinting() != shouldSprint) {
            npcPlayer.setSprinting(shouldSprint);
        }
    }

    private void tickIdle(Location loc, World world) {
        int oreRadius = plugin.getConfig().getInt("ore-search-radius", 6);
        Block ore = findNearbyOre(loc, oreRadius);
        if (ore != null) {
            Location oreApproach = findApproachLocation(ore);
            if (oreApproach != null) {
                targetOre = ore;
                state = State.MOVING_TO_ORE;
                npc.getNavigator().setTarget(oreApproach);
                return;
            }
            // Немає безпечного підходу до цієї руди - пробуємо дерево/моба нижче, ще раз повернемось пізніше
        }

        int treeRadius = plugin.getConfig().getInt("tree-search-radius", 5);
        Block tree = findNearbyLog(loc, treeRadius);
        if (tree != null) {
            Location approach = findApproachLocation(tree);
            if (approach == null) {
                // Немає безпечного підходу до цього дерева - шукаємо інше наступного разу
                wander(loc, world);
                return;
            }
            targetTree = tree;
            state = State.MOVING_TO_TREE;
            npc.getNavigator().setTarget(approach);
            return;
        }

        int mobRadius = plugin.getConfig().getInt("mob-search-radius", 6);
        LivingEntity mob = findNearbyPassiveMob(loc, mobRadius);
        if (mob != null) {
            targetMob = mob;
            state = State.MOVING_TO_MOB;
            npc.getNavigator().setTarget(mob, false);
            return;
        }

        wander(loc, world);
    }

    // Шукає сусідню з деревом клітинку, куди NPC реально може стати (не в сам стовбур)
    private Location findApproachLocation(Block tree) {
        World world = tree.getWorld();
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] off : offsets) {
            Block side = world.getBlockAt(tree.getX() + off[0], tree.getY(), tree.getZ() + off[1]);
            Block below = side.getRelative(0, -1, 0);
            Block above = side.getRelative(0, 1, 0);
            if (side.getType().isAir() && above.getType().isAir()
                    && (below.getType().isSolid() || isLog(below.getType()))) {
                return side.getLocation().add(0.5, 0, 0.5);
            }
        }
        return null;
    }

    private void tickMovingToTree() {
        if (targetTree == null || !isLog(targetTree.getType())) {
            state = State.IDLE;
            return;
        }
        Navigator nav = npc.getNavigator();
        if (!nav.isNavigating() || npc.getEntity().getLocation().distanceSquared(targetTree.getLocation()) <= 6.25) {
            // Дійшли (в радіусі ~2.5 блока)
            state = State.MINING;
            actionProgressTicks = 0;
        }
    }

    private void tickMining() {
        if (targetTree == null || !isLog(targetTree.getType())) {
            state = State.IDLE;
            return;
        }
        World world = targetTree.getWorld();
        // Анімація/ефект видобутку (без прив'язки до BlockData - стабільно на всіх версіях)
        world.spawnParticle(Particle.CRIT, targetTree.getLocation().add(0.5, 0.5, 0.5), 6, 0.2, 0.2, 0.2, 0.0);
        world.playSound(targetTree.getLocation(), org.bukkit.Sound.BLOCK_WOOD_HIT, 1f, 1f);
        if (npc.getEntity() instanceof Player npcPlayer) {
            PlayerAnimation.ARM_SWING.play(npcPlayer);
        }

        // Дивимось на блок, поки рубаємо
        faceBlock(targetTree);

        actionProgressTicks += 10;
        int required = plugin.getConfig().getInt("mining-duration-ticks", 40);
        if (actionProgressTicks >= required) {
            world.playSound(targetTree.getLocation(), org.bukkit.Sound.BLOCK_WOOD_BREAK, 1f, 1f);
            world.spawnParticle(Particle.CLOUD, targetTree.getLocation().add(0.5, 0.5, 0.5), 15, 0.3, 0.3, 0.3, 0.02);
            // Замість breakNaturally() (яке одразу кидає дроп на землю) забираємо дроп
            // "у кишеню" NPC - висипле все зібране пізніше, коли гравець підійде
            Collection<ItemStack> drops = targetTree.getDrops();
            collectDrops(drops);
            targetTree.setType(Material.AIR);
            targetTree = null;
            state = State.IDLE;
        }
    }

    private void tickMovingToOre() {
        if (targetOre == null || !isOre(targetOre.getType())) {
            state = State.IDLE;
            return;
        }
        Navigator nav = npc.getNavigator();
        if (!nav.isNavigating() || npc.getEntity().getLocation().distanceSquared(targetOre.getLocation()) <= 6.25) {
            // Дійшли (в радіусі ~2.5 блока)
            state = State.MINING_ORE;
            actionProgressTicks = 0;
        }
    }

    private void tickMiningOre() {
        if (targetOre == null || !isOre(targetOre.getType())) {
            state = State.IDLE;
            return;
        }
        World world = targetOre.getWorld();
        world.spawnParticle(Particle.CRIT, targetOre.getLocation().add(0.5, 0.5, 0.5), 6, 0.2, 0.2, 0.2, 0.0);
        world.playSound(targetOre.getLocation(), org.bukkit.Sound.BLOCK_STONE_HIT, 1f, 1f);
        if (npc.getEntity() instanceof Player npcPlayer) {
            PlayerAnimation.ARM_SWING.play(npcPlayer);
        }

        // Дивимось на блок, поки видобуваємо
        faceBlock(targetOre);

        actionProgressTicks += 10;
        int required = plugin.getConfig().getInt("ore-mining-duration-ticks", 70);
        if (actionProgressTicks >= required) {
            world.playSound(targetOre.getLocation(), org.bukkit.Sound.BLOCK_STONE_BREAK, 1f, 1f);
            world.spawnParticle(Particle.CLOUD, targetOre.getLocation().add(0.5, 0.5, 0.5), 15, 0.3, 0.3, 0.3, 0.02);
            // Розрахунок дропу через "віртуальну" кирку - гарантує коректний дроп (напр. сирий
            // алмаз/залізо) незалежно від того, чим "насправді" копає NPC
            Collection<ItemStack> drops = targetOre.getDrops(ORE_MINING_TOOL);
            collectDrops(drops);
            targetOre.setType(Material.AIR);
            targetOre = null;
            state = State.IDLE;
        }
    }

    private void tickMovingToMob() {
        if (targetMob == null || targetMob.isDead() || !targetMob.isValid()) {
            targetMob = null;
            state = State.IDLE;
            return;
        }
        Navigator nav = npc.getNavigator();
        if (npc.getEntity().getLocation().distanceSquared(targetMob.getLocation()) <= 4.0) {
            state = State.ATTACKING;
            hitsLanded = 0;
            actionProgressTicks = 0;
        } else if (!nav.isNavigating()) {
            nav.setTarget(targetMob, false);
        }
    }

    private void tickAttacking() {
        if (targetMob == null || targetMob.isDead() || !targetMob.isValid()) {
            targetMob = null;
            state = State.IDLE;
            return;
        }
        Location npcLoc = npc.getEntity().getLocation();
        if (npcLoc.distanceSquared(targetMob.getLocation()) > 9.0) {
            state = State.MOVING_TO_MOB;
            return;
        }

        actionProgressTicks += 10;
        // Удар раз на ~0.7 секунди
        if (actionProgressTicks >= 14) {
            actionProgressTicks = 0;
            faceEntity(targetMob);
            if (npc.getEntity() instanceof Player npcPlayer) {
                PlayerAnimation.ARM_SWING.play(npcPlayer);
            }
            targetMob.damage(2.0, npc.getEntity());
            npcLoc.getWorld().spawnParticle(Particle.CRIT, targetMob.getLocation().add(0, 1, 0), 8);
            npcLoc.getWorld().playSound(npcLoc, org.bukkit.Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1f);
            hitsLanded++;

            int required = plugin.getConfig().getInt("hits-to-kill-mob", 3);
            if (hitsLanded >= required || targetMob.isDead()) {
                if (!targetMob.isDead()) {
                    targetMob.setHealth(0.0);
                }
                targetMob = null;
                state = State.IDLE;
            }
        }
    }

    private void wander(Location loc, World world) {
        if (npc.getNavigator().isNavigating()) return;
        // Невеликий випадковий рух, щоб NPC не стояв на місці вічно
        if (random.nextInt(4) == 0) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 3 + random.nextDouble() * 5;
            Vector offset = new Vector(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
            Location dest = loc.clone().add(offset);
            dest.setY(world.getHighestBlockYAt(dest.getBlockX(), dest.getBlockZ()) + 1);
            npc.getNavigator().setTarget(dest);
        }
    }

    // Кожні voice.memorize-interval-seconds секунд намагається "запам'ятати" нову репліку -
    // бере голосний відрізок з буфера гравця, спотворює і додає у список memorizedVoiceLines
    // (список обмежений voice.memorize-max-lines - найстаріші репліки витісняються новими).
    private void tickVoiceMemory() {
        if (!voiceEnabledForThisNpc || skinOwnerId == null) {
            return;
        }

        ticksUntilNextMemorize -= 10;
        if (ticksUntilNextMemorize > 0) {
            return;
        }
        int intervalSeconds = plugin.getConfig().getInt("voice.memorize-interval-seconds", 6);
        ticksUntilNextMemorize = Math.max(1, intervalSeconds) * 20;

        short[] raw = plugin.getVoiceCaptureManager().takeSnapshot(skinOwnerId);
        boolean debug = plugin.getConfig().getBoolean("voice.debug", true);
        if (raw == null || raw.length < 4800) {
            if (debug) {
                plugin.getLogger().info("[Voice] NPC #" + npc.getId() + ": нема чого запам'ятати ("
                        + (raw == null ? "0" : raw.length) + " семплів) - гравець зараз мовчить.");
            }
            return;
        }

        double pitch = 0.75 + random.nextDouble() * 0.2;
        short[] distorted = VoiceDistortion.apply(raw, pitch, 3);

        int maxLines = plugin.getConfig().getInt("voice.memorize-max-lines", 6);
        memorizedVoiceLines.add(distorted);
        while (memorizedVoiceLines.size() > Math.max(1, maxLines)) {
            memorizedVoiceLines.remove(0); // витісняємо найстарішу репліку новою
        }

        String skinOwnerName = Bukkit.getOfflinePlayer(skinOwnerId).getName();
        plugin.getVoiceLineArchive().maybeArchive(skinOwnerId, skinOwnerName, distorted);

        if (debug) {
            plugin.getLogger().info("[Voice] NPC #" + npc.getId() + ": запам'ятав нову репліку ("
                    + distorted.length + " семплів, ~" + String.format("%.1f", distorted.length / 48000.0)
                    + " сек). Усього запам'ятано реплік: " + memorizedVoiceLines.size() + ".");
        }
    }

    // Поки NPC живий і має хоч одну запам'ятовану репліку - раз у voice.playback-gap-min/max-seconds
    // (проміжок МІЖ репліками, не тривалість самої репліки) програє випадкову з них.
    private void tickVoicePlayback() {
        if (!voiceEnabledForThisNpc || memorizedVoiceLines.isEmpty()) {
            return;
        }

        if (ticksUntilNextPlayback < 0) {
            // Щойно з'явилась перша репліка - плануємо перше відтворення з невеликою затримкою
            ticksUntilNextPlayback = randomGapTicks();
            return;
        }

        ticksUntilNextPlayback -= 10;
        if (ticksUntilNextPlayback > 0) {
            return;
        }

        playRandomMemorizedLine();
    }

    private int randomGapTicks() {
        int minSeconds = plugin.getConfig().getInt("voice.playback-gap-min-seconds", 2);
        int maxSeconds = plugin.getConfig().getInt("voice.playback-gap-max-seconds", 3);
        if (maxSeconds < minSeconds) maxSeconds = minSeconds;
        int seconds = minSeconds + (maxSeconds > minSeconds ? random.nextInt(maxSeconds - minSeconds + 1) : 0);
        return Math.max(1, seconds) * 20;
    }

    private void playRandomMemorizedLine() {
        if (!npc.isSpawned() || npc.getEntity() == null) {
            return;
        }
        boolean debug = plugin.getConfig().getBoolean("voice.debug", true);
        short[] line = memorizedVoiceLines.get(random.nextInt(memorizedVoiceLines.size()));
        Location npcLoc = npc.getEntity().getLocation();

        currentVoicePlayer = plugin.getVoicePlaybackService().playAt(npcLoc, line);
        if (currentVoicePlayer == null) {
            // Відтворення не вдалось запустити (SVC не готовий/канал не створився) - спробуємо пізніше
            ticksUntilNextPlayback = randomGapTicks();
            return;
        }

        // Плануємо наступне відтворення: тривалість поточного кліпу + випадковий проміжок 2-3 сек
        int clipDurationTicks = (int) Math.ceil(line.length / 48000.0 * 20.0);
        ticksUntilNextPlayback = clipDurationTicks + randomGapTicks();

        if (debug) {
            plugin.getLogger().info("[Voice] NPC #" + npc.getId() + ": відтворюю запам'ятовану репліку ("
                    + line.length + " семплів, ~" + String.format("%.1f", line.length / 48000.0)
                    + " сек) з " + memorizedVoiceLines.size() + " наявних. Наступна приблизно через "
                    + String.format("%.1f", ticksUntilNextPlayback / 20.0) + " сек.");
        }
    }

    // Примусово зупиняє звук, що зараз грає (якщо грає) - викликається, коли NPC зникає,
    // щоб голос не продовжував лунати вже після того, як NPC візуально пропав.
    private void stopVoicePlayback() {
        if (currentVoicePlayer != null) {
            try {
                currentVoicePlayer.stopPlaying();
            } catch (Exception e) {
                plugin.getLogger().warning("[Voice] Не вдалося зупинити відтворення для NPC #" + npc.getId()
                        + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            currentVoicePlayer = null;
        }
    }

    private void despawnWithSmoke() {
        stopVoicePlayback();
        if (npc.isSpawned() && npc.getEntity() != null) {
            Location loc = npc.getEntity().getLocation();
            World world = loc.getWorld();
            if (world != null) {
                // Все, що NPC назбирав (руда, дерево, здобич з мобів), висипається тут -
                // саме заради цього і зникнення при наближенні гравця більше не "з'їдає" здобич
                if (!collectedDrops.isEmpty()) {
                    for (ItemStack item : collectedDrops) {
                        if (item != null && item.getType() != Material.AIR) {
                            world.dropItemNaturally(loc, item);
                        }
                    }
                    if (plugin.getConfig().getBoolean("voice.debug", true)) {
                        plugin.getLogger().info("NPC #" + npc.getId() + " висипав зібрану здобич ("
                                + collectedDrops.size() + " стеків) при зникненні у " + loc.getBlockX()
                                + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ".");
                    }
                    collectedDrops.clear();
                }
                world.spawnParticle(Particle.LARGE_SMOKE, loc.add(0, 1, 0), 30, 0.3, 0.5, 0.3, 0.02);
                world.spawnParticle(Particle.CLOUD, loc, 15, 0.3, 0.5, 0.3, 0.05);
                world.playSound(loc, org.bukkit.Sound.ENTITY_ITEM_BREAK, 1f, 0.7f);
            }
        }
        npc.destroy();
        plugin.getOurNpcIds().remove(npc.getId());
        plugin.unregisterTask(npc.getId());
        if (skinOwnerId != null) {
            plugin.getVoiceCaptureManager().untrack(skinOwnerId);
        }
        cancel();
    }

    // ---- Допоміжні методи пошуку ----

    private Player findNearestPlayer(Location loc, double maxDist) {
        double maxDistSq = maxDist * maxDist;
        Player nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (Player p : loc.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(loc);
            if (d <= maxDistSq && d < nearestDistSq) {
                nearest = p;
                nearestDistSq = d;
            }
        }
        return nearest;
    }

    private Block findNearbyLog(Location center, int radius) {
        World world = center.getWorld();
        Block closest = null;
        double closestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block b = world.getBlockAt(center.getBlockX() + dx, center.getBlockY() + dy, center.getBlockZ() + dz);
                    if (isLog(b.getType())) {
                        double d = b.getLocation().distanceSquared(center);
                        if (d < closestDist) {
                            closestDist = d;
                            closest = b;
                        }
                    }
                }
            }
        }
        return closest;
    }

    private LivingEntity findNearbyPassiveMob(Location center, int radius) {
        World world = center.getWorld();
        LivingEntity closest = null;
        double closestDist = Double.MAX_VALUE;
        for (Entity e : world.getNearbyEntities(center, radius, radius, radius)) {
            if (e instanceof Animals animal && !animal.isDead()) {
                double d = e.getLocation().distanceSquared(center);
                if (d < closestDist) {
                    closestDist = d;
                    closest = animal;
                }
            }
        }
        return closest;
    }

    private boolean isLog(Material material) {
        return material.name().endsWith("_LOG") || material.name().endsWith("_WOOD")
                && !material.name().contains("STRIPPED");
    }

    private boolean isOre(Material material) {
        return material.name().endsWith("_ORE") || material == Material.ANCIENT_DEBRIS;
    }

    private Block findNearbyOre(Location center, int radius) {
        World world = center.getWorld();
        Block closest = null;
        double closestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block b = world.getBlockAt(center.getBlockX() + dx, center.getBlockY() + dy, center.getBlockZ() + dz);
                    if (isOre(b.getType())) {
                        double d = b.getLocation().distanceSquared(center);
                        if (d < closestDist) {
                            closestDist = d;
                            closest = b;
                        }
                    }
                }
            }
        }
        return closest;
    }

    private void faceBlock(Block block) {
        if (npc.getEntity() == null) return;
        Location eye = npc.getEntity().getLocation();
        Location target = block.getLocation().add(0.5, 0.5, 0.5);
        eye.setDirection(target.toVector().subtract(eye.toVector()));
        npc.faceLocation(target);
    }

    private void faceEntity(Entity target) {
        npc.faceLocation(target.getLocation());
    }
}