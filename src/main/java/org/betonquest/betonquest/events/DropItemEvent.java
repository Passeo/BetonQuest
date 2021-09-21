package org.betonquest.betonquest.events;

import org.betonquest.betonquest.BetonQuest;
import org.betonquest.betonquest.Instruction;
import org.betonquest.betonquest.VariableNumber;
import org.betonquest.betonquest.api.QuestEvent;
import org.betonquest.betonquest.compatibility.protocollib.hider.EntityHider;
import org.betonquest.betonquest.exceptions.InstructionParseException;
import org.betonquest.betonquest.exceptions.QuestRuntimeException;
import org.betonquest.betonquest.item.QuestItem;
import org.betonquest.betonquest.utils.PlayerConverter;
import org.betonquest.betonquest.utils.location.CompoundLocation;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings({"PMD.CommentRequired"})
public class DropItemEvent extends QuestEvent implements Listener {

    private final Instruction.Item[] questItems;
    private final CompoundLocation location;
    private final boolean isProtected;
    private final boolean isPrivate;

    /**
     * Saves the entity of each dropped item alongside the player that is allowed to pick the item up.
     */
    private final Map<Entity, UUID> entityPlayerMap = new HashMap<>();
    private EntityHider hider;

    public DropItemEvent(final Instruction instruction) throws InstructionParseException {
        super(instruction, true);

        Bukkit.getPluginManager().registerEvents(this, BetonQuest.getInstance());

        questItems = instruction.getItemList();

        final String location = instruction.getOptional("location");
        if (location == null) {
            throw new InstructionParseException("No drop location given!");
        } else {
            this.location = instruction.getLocation(location);
        }

        final String isProtected = instruction.getOptional("protected");
        this.isProtected = isProtected != null && isProtected.equals("true");

        final String isPrivate = instruction.getOptional("private");
        this.isPrivate = isPrivate != null && isPrivate.equals("true");

        if (this.isPrivate) {
            if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
                throw new InstructionParseException("You need to install ProtocolLib to use private item drops!");
            } else {
                hider = new EntityHider(BetonQuest.getInstance(), EntityHider.Policy.WHITELIST);
            }
        }
    }

    @Override
    protected Void execute(final String playerID) throws QuestRuntimeException {
        for (final Instruction.Item item : questItems) {
            final Player player = PlayerConverter.getPlayer(playerID);
            final QuestItem questItem = item.getItem();
            final VariableNumber amount = item.getAmount();

            final ItemStack generateItem = questItem.generate(amount.getInt(playerID), playerID);
            final Location loc = location.getLocation(playerID);
            final Entity droppedItem = loc.getWorld().dropItem(loc, generateItem);

            if (isPrivate) {
                entityPlayerMap.put(droppedItem, player.getUniqueId());
                hider.showEntity(player, droppedItem);
            }
        }
        return null;
    }


    @EventHandler(ignoreCancelled = true)
    public void onPickupItem(final EntityPickupItemEvent event) {
        final Entity item = event.getItem();
        if (!entityPlayerMap.containsKey(item)) {
            return;
        }

        final UUID ownerUUID = entityPlayerMap.get(item);
        final Entity pickupEntity = event.getEntity();
        if ((pickupEntity instanceof Player)) {
            final Player pickupPlayer = (Player) pickupEntity;
            if (pickupPlayer.getUniqueId().equals(ownerUUID)) {
                entityPlayerMap.remove(item);
                return;
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerJoin(final PlayerJoinEvent event) {
        for (final Entity item : entityPlayerMap.keySet()) {
            if (!event.getPlayer().getUniqueId().equals(entityPlayerMap.get(item))) {
                hider.hideEntity(event.getPlayer(), item);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMergingItem(final ItemMergeEvent event) {
        //TODO: Define default behavior: Shouldn't private items always be protected from being converted into public
        //      items by being merged with another players private items?
        if (isProtected) {
            for (final Entity item : entityPlayerMap.keySet()) {
                if (event.getEntity().equals(item)) {
                    event.setCancelled(true);
                }
            }

        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDespawn(final ItemDespawnEvent event) {
        if (isProtected) {
            for (final Entity item : entityPlayerMap.keySet()) {
                if (event.getEntity().equals(item) && event.getEntity().getType() == EntityType.DROPPED_ITEM) {
                    event.setCancelled(true);
                    //TODO: What happens to the items live time? It might have to be reset so this event doesn't fire
                    //      every tick from now on.
                }
            }
        } else {
            for (final Entity item : entityPlayerMap.keySet()) {
                if (event.getEntity().equals(item)) {
                    entityPlayerMap.remove(item);
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(final EntityDamageEvent event) {
        for (final Entity item : entityPlayerMap.keySet()) {
            if (event.getEntity().getType() == EntityType.DROPPED_ITEM
                    && event.getEntity().equals(item)) {
                entityPlayerMap.remove(item);
            }
        }
    }

    //TODO: What happens when the item get's sucked into a hopper?

    //TODO: What happens when the item get's moved (by liquids)?
}
