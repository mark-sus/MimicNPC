package me.mvk.mimicNPC;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * When NPC kill mob or mine blocks he save items in own inventory 
 * and when you scare NPC he drop all mined loot
 */
public class NpcLootListener implements Listener {

    private final MimicNPCPlugin plugin;

    public NpcLootListener(MimicNPCPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Entity killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        if (!CitizensAPI.getNPCRegistry().isNPC(killer)) {
            return;
        }
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(killer);
        if (npc == null || !plugin.getOurNpcIds().contains(npc.getId())) {
            return;
        }

        NpcBehaviorTask task = plugin.getTaskForNpc(npc.getId());
        if (task == null) {
            return;
        }

        task.collectDrops(event.getDrops());
        event.getDrops().clear();
        event.setDroppedExp(0);
    }
}
