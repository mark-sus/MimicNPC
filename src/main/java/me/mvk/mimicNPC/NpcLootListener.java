package me.mvk.mimicNPC;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * Коли наш NPC вбиває мирного моба (див. NpcBehaviorTask.tickAttacking), звичайний
 * EntityDeathEvent все одно спрацьовує і дроп падає на землю сам по собі. Цей слухач
 * перехоплює такі смерті, забирає дроп собі в "інвентар" NPC (щоб він виглядав так,
 * ніби зібрав здобич) і прибирає його зі стандартного списку дропу, щоб предмети
 * не з'явились на землі одразу. Пізніше NpcBehaviorTask сам висипле все зібране,
 * коли гравець підійде близько (або коли NPC зникне за часом життя).
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