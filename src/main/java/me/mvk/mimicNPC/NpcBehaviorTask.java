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
 * Every half a second checks the NPC encirclement and decides what to do:
 * 1) if there is a player nearby - disappear with smoke
 * 2) if there is a tree - go to it and "cut"
 * 3) if there is a peaceful mob nearby - go to it and hit
 * 4) otherwise - accidentally wander
 * Parallel (if NPC "talking" - see voice.scare-chance) while NPC is alive,
 * he periodically remembers new remarks of the player's voice and at intervals
 * voice.playback-gap-min/max-seconds reproduces random from memorized.
 * The sound forcibly stops at the time of the NPC disappearance.
 */
public class NpcBehaviorTask extends BukkitRunnable {

    private final MimicNPCPlugin plugin;
    private final NPC npc;
    private final Random random = new Random();

    private enum State { IDLE, MOVING_TO_TREE, MINING, MOVING_TO_ORE, MINING_ORE, MOVING_TO_MOB, ATTACKING }

    // Tool used only to calculate ore (not shown
    // in the hand of NPC) - take an unfading unbreakable pickaxe so that NPC can always "mine"
    // any ore regardless of its level of hardness (up to Ancient Debris)
    private static final ItemStack ORE_MINING_TOOL = new ItemStack(Material.NETHERITE_PICKAXE);

    private State state = State.IDLE;
    private Block targetTree;
    private Block targetOre;
    private LivingEntity targetMob;
    private int actionProgressTicks = 0;
    private int hitsLanded = 0;
    private int aliveTicks = 0;

    // NPC mined resources
    private final List<ItemStack> collectedDrops = new ArrayList<>();

    private final UUID skinOwnerId;

    private final boolean voiceEnabledForThisNpc;

    private final List<short[]> memorizedVoiceLines = new ArrayList<>();

    private AudioPlayer currentVoicePlayer;

    private int ticksUntilNextMemorize = 0;
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
        this.runTaskTimer(plugin, 20L, 10L);
    }

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

        // 1. Check how close player
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

    // Realistic NPC movement
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
        }

        int treeRadius = plugin.getConfig().getInt("tree-search-radius", 5);
        Block tree = findNearbyLog(loc, treeRadius);
        if (tree != null) {
            Location approach = findApproachLocation(tree);
            if (approach == null) {
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
        // Mining animation
        world.spawnParticle(Particle.CRIT, targetTree.getLocation().add(0.5, 0.5, 0.5), 6, 0.2, 0.2, 0.2, 0.0);
        world.playSound(targetTree.getLocation(), org.bukkit.Sound.BLOCK_WOOD_HIT, 1f, 1f);
        if (npc.getEntity() instanceof Player npcPlayer) {
            PlayerAnimation.ARM_SWING.play(npcPlayer);
        }

        // look on the block when mine
        faceBlock(targetTree);

        actionProgressTicks += 10;
        int required = plugin.getConfig().getInt("mining-duration-ticks", 40);
        if (actionProgressTicks >= required) {
            world.playSound(targetTree.getLocation(), org.bukkit.Sound.BLOCK_WOOD_BREAK, 1f, 1f);
            world.spawnParticle(Particle.CLOUD, targetTree.getLocation().add(0.5, 0.5, 0.5), 15, 0.3, 0.3, 0.3, 0.02);
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

        faceBlock(targetOre);

        actionProgressTicks += 10;
        int required = plugin.getConfig().getInt("ore-mining-duration-ticks", 70);
        if (actionProgressTicks >= required) {
            world.playSound(targetOre.getLocation(), org.bukkit.Sound.BLOCK_STONE_BREAK, 1f, 1f);
            world.spawnParticle(Particle.CLOUD, targetOre.getLocation().add(0.5, 0.5, 0.5), 15, 0.3, 0.3, 0.3, 0.02);
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
        if (random.nextInt(4) == 0) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 3 + random.nextDouble() * 5;
            Vector offset = new Vector(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
            Location dest = loc.clone().add(offset);
            dest.setY(world.getHighestBlockYAt(dest.getBlockX(), dest.getBlockZ()) + 1);
            npc.getNavigator().setTarget(dest);
        }
    }


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
            memorizedVoiceLines.remove(0);
        }

        String skinOwnerName = Bukkit.getOfflinePlayer(skinOwnerId).getName();
        plugin.getVoiceLineArchive().maybeArchive(skinOwnerId, skinOwnerName, distorted);

        if (debug) {
            plugin.getLogger().info("[Voice] NPC #" + npc.getId() + ": запам'ятав нову репліку ("
                    + distorted.length + " семплів, ~" + String.format("%.1f", distorted.length / 48000.0)
                    + " сек). Усього запам'ятано реплік: " + memorizedVoiceLines.size() + ".");
        }
    }

    // Playback random saved player voice line
    private void tickVoicePlayback() {
        if (!voiceEnabledForThisNpc || memorizedVoiceLines.isEmpty()) {
            return;
        }

        if (ticksUntilNextPlayback < 0) {
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
            ticksUntilNextPlayback = randomGapTicks();
            return;
        }
        int clipDurationTicks = (int) Math.ceil(line.length / 48000.0 * 20.0);
        ticksUntilNextPlayback = clipDurationTicks + randomGapTicks();

        if (debug) {
            plugin.getLogger().info("[Voice] NPC #" + npc.getId() + ": відтворюю запам'ятовану репліку ("
                    + line.length + " семплів, ~" + String.format("%.1f", line.length / 48000.0)
                    + " сек) з " + memorizedVoiceLines.size() + " наявних. Наступна приблизно через "
                    + String.format("%.1f", ticksUntilNextPlayback / 20.0) + " сек.");
        }
    }

    // Stop playing voice line vhen NPC despawn
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

    // ---- Help search methods ----

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
