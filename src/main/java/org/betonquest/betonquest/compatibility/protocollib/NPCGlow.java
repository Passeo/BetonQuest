package org.betonquest.betonquest.compatibility.protocollib;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.async.AsyncListenerHandler;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.ListeningWhitelist;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.injector.GamePhase;
import com.comphenix.protocol.wrappers.WrappedWatchableObject;
import lombok.CustomLog;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.CitizensReloadEvent;
import net.citizensnpcs.api.event.NPCDeathEvent;
import net.citizensnpcs.api.event.NPCRemoveEvent;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.npc.NPC;
import org.apache.commons.lang3.EnumUtils;
import org.betonquest.betonquest.BetonQuest;
import org.betonquest.betonquest.api.config.quest.QuestPackage;
import org.betonquest.betonquest.api.profiles.OnlineProfile;
import org.betonquest.betonquest.compatibility.protocollib.api.GlowAPI;
import org.betonquest.betonquest.config.Config;
import org.betonquest.betonquest.exceptions.ObjectNotFoundException;
import org.betonquest.betonquest.id.ConditionID;
import org.betonquest.betonquest.utils.PlayerConverter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * NPCGlow class
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.GodClass"})
@CustomLog
public final class NPCGlow extends BukkitRunnable implements Listener {

    /**
     * instance of NPCGlow
     */
    private static NPCGlow instance;
    /**
     * List of conditions for specific NPC that will get glow
     */
    private final List<GlowState> glowStates;
    /**
     * instance of GlowAPI
     */
    private final GlowAPI glowAPI;

    private final AsyncListenerHandler metadataListener;
    private final EntityMetadataListener entityMetadata;

    /**
     * Constructor of NPCGlow instance
     */
    public NPCGlow() {
        super();
        glowStates = new ArrayList<>();
        glowAPI = new GlowAPI();
        Bukkit.getScheduler().runTaskLater(BetonQuest.getInstance(), this::loadFromConfig, 5L);
        runTaskTimerAsynchronously(BetonQuest.getInstance(), 0, 5);
        Bukkit.getPluginManager().registerEvents(this, BetonQuest.getInstance());
        entityMetadata = new EntityMetadataListener();
        metadataListener = ProtocolLibrary.getProtocolManager()
                .getAsynchronousManager()
                .registerAsyncHandler(entityMetadata);
        metadataListener.syncStart();
    }

    /**
     * Starts (or restarts) the NPCGlow. Used for reloading this feature.
     */
    public static void start() {
        synchronized (NPCGlow.class) {
            if (instance != null) {
                instance.stop();
            }
            instance = new NPCGlow();
        }
    }

    /**
     * @return the currently used NPCGlow instance
     */
    public static NPCGlow getInstance() {
        return instance;
    }

    /**
     * load all data from config
     */
    @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.CyclomaticComplexity"})
    private void loadFromConfig() {
        for (final QuestPackage cfgPackage : Config.getPackages().values()) {
            final ConfigurationSection custom = cfgPackage.getConfig();
            final ConfigurationSection section = custom.getConfigurationSection("glow_npc");
            if (section == null) {
                continue;
            }

            for (final String key : section.getKeys(false)) {
                final String npcSectionId = section.getString(key + ".id");
                final String color = section.getString(key + ".color");
                final String rawConditions = section.getString(key + ".conditions");
                final Set<ConditionID> conditions = parseConditions(cfgPackage, key, rawConditions);
                if (npcSectionId == null) {
                    LOG.warn(cfgPackage, "No ID found in glow_npc for '" + key + "'");
                    continue;
                }
                final int npcId;
                try {
                    npcId = Integer.parseInt(npcSectionId);
                } catch (final NumberFormatException e) {
                    LOG.warn(cfgPackage, "NPC ID '" + npcSectionId + "' is not a valid number, in glow_npc", e);
                    continue;
                }
                if (CitizensAPI.getNPCRegistry().getById(npcId) == null) {
                    LOG.warn(cfgPackage, "NPC Glow could not update Glowing for npc " + npcSectionId + ": No npc with this id found!");
                    continue;
                }
                ChatColor chatColor = ChatColor.WHITE;
                if (color != null && EnumUtils.isValidEnum(ChatColor.class, color.toUpperCase(Locale.ROOT))) {
                    chatColor = ChatColor.valueOf(color.toUpperCase(Locale.ROOT));
                }

                final GlowState glowState = new GlowState(npcId, conditions, chatColor, new HashSet<>());
                glowStates.add(glowState);
            }
        }
    }

    /**
     * Parses the provided instruction string into a set of conditions.
     *
     * @param questPackage  QuestPackage that contain the raw conditions
     * @param key           Name of the Glow Section
     * @param rawConditions String of raw conditions
     * @return Set Collections of ConditionID
     */
    private Set<ConditionID> parseConditions(final QuestPackage questPackage, final String key, final String rawConditions) {
        final Set<ConditionID> conditions = new HashSet<>();
        if (rawConditions == null) {
            return conditions;
        }
        for (final String condition : rawConditions.split(",")) {
            try {
                conditions.add(new ConditionID(questPackage, condition));
            } catch (final ObjectNotFoundException e) {
                LOG.warn(questPackage, "Condition '" + condition + "' does not exist, in glow_npc with ID " + key, e);
            }
        }
        return conditions;
    }

    /**
     * Applies the glow to one NPC if profile meets the conditions.
     *
     * @param profile profile that will get checked
     * @param npc     the npc that will get checked
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    public void applyVisibility(final OnlineProfile profile, final NPC npc) {
        if(npc == null){
            return;
        }

        if (!npc.isSpawned()) {
            return;
        }

        final Integer npcId = npc.getId();
        if (glowStates.stream().noneMatch(glowState -> glowState.npcID().equals(npcId))) {
            return;
        }

        final Entity entity = npc.getEntity();
        glowStates.stream()
                .filter((glowState) -> glowState.npcID().equals(npcId))
                .forEach(glowState -> {
                    final Set<ConditionID> conditions = glowState.conditions();

                    final Collection<OnlineProfile> glowingProfiles = glowState.activeProfiles();
                    if (conditions.isEmpty() || BetonQuest.conditions(profile, conditions)) {
                        if (glowingProfiles.add(profile)) {
                            glowAPI.sendGlowPacket(entity, glowState.color(), true, profile);
                        }
                    } else {
                        if (glowingProfiles.remove(profile)) {
                            glowAPI.sendGlowPacket(entity, glowState.color(), false, profile);
                        }
                    }
                });
    }

    /**
     * Updates all NPCs for all profiles.
     */
    public void applyAll() {
        for (final OnlineProfile onlineProfile : PlayerConverter.getOnlineProfiles()) {
            glowStates.forEach((glowState) -> applyVisibility(onlineProfile, CitizensAPI.getNPCRegistry().getById(glowState.npcID())));
        }

    }

    /**
     * Applies glow to npc for all online profiles if the conditions are met.
     *
     * @param npc npc that gets checked
     */
    public void applyGlow(final NPC npc) {
        if (!npc.getOwningRegistry().equals(CitizensAPI.getNPCRegistry())) {
            return;
        }
        PlayerConverter.getOnlineProfiles().parallelStream()
                .forEach((profile) -> applyVisibility(profile, npc));
    }

    public boolean isGlowing(final NPC npc, final OnlineProfile profile) {
        final int npcId = npc.getId();
        return glowStates.parallelStream()
                .filter((state) -> state.npcID == npcId)
                .anyMatch((state) -> state.activeProfiles.contains(profile));
    }

    /**
     * Starts the Runnable.
     */
    @Override
    public void run() {
        applyAll();
    }

    /**
     * Resets all glowing NPCs.
     *
     * @param profiles List of profiles that will get the packet
     */
    public void resetGlow(final Collection<? extends OnlineProfile> profiles) {
        glowStates.parallelStream().forEach((glowState) -> {
            final NPC npc = CitizensAPI.getNPCRegistry().getById(glowState.npcID);
            if (npc == null) {
                return;
            }
            final Entity entity = npc.getEntity();
            glowAPI.sendGlowPacket(entity, glowState.color, false, profiles);
        });
    }

    /**
     * Stops the NPCGlow instance, cleaning up all maps, Runnable, Listener, etc. And Reset all the glowing npc.
     */
    public void stop() {
        cancel();
        resetGlow(PlayerConverter.getOnlineProfiles());
        HandlerList.unregisterAll(this);
        ProtocolLibrary.getProtocolManager()
                .getAsynchronousManager()
                .unregisterAsyncHandler(entityMetadata);
        metadataListener.stop();
    }

    /**
     * When an NPC is removed, the current glowing profiles are removed as well.
     * This is done to avoid the NPC glowing for a split second after getting recreated.
     *
     * @param event NPCRemoveEvent
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNPCRemove(final NPCRemoveEvent event) {
        clearActiveProfiles(event.getNPC().getId());
    }

    /**
     * When an NPC dies, the current glowing profiles are removed.
     * This is done to avoid the NPC glowing for a split second after respawn.
     *
     * @param event NPCDeathEvent
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNPCDeath(final NPCDeathEvent event) {
        clearActiveProfiles(event.getNPC().getId());
    }

    private void clearActiveProfiles(final Integer npcId) {
        glowStates.forEach((glowState) -> {
            if (glowState.npcID().equals(npcId)) {
                glowState.activeProfiles().clear();
            }
        });
    }

    /**
     * Restart the NPCGlow Instance when citizens plugin is getting reload
     *
     * @param event CitizensReload
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCitizensReload(final CitizensReloadEvent event) {
        start();
    }

    /**
     * apply glowing to npc that just spawn (if it registered on map).
     *
     * @param event NPCSpawn
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNPCSpawn(final NPCSpawnEvent event) {
        applyGlow(event.getNPC());
    }

    /**
     * Removes the profile that just quit from all maps.
     *
     * @param event PlayerQuitEvent
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerLogout(final PlayerQuitEvent event) {
        glowStates.forEach((glowState) -> glowState.activeProfiles.remove(PlayerConverter.getID(event.getPlayer())));
    }

    /**
     * Data class that stores different configurations of glowing NPCs.
     * A single NPC can have multiple GlowStates, each with a different color, condition set and active profiles.
     *
     * @param npcID          ID of NPC that has this state
     * @param conditions     List of conditions for when to activate the state
     * @param color          Color of the glow state
     * @param activeProfiles Collection of profiles that can see this state
     */
    private record GlowState(Integer npcID, Set<ConditionID> conditions, ChatColor color,
                             Collection<OnlineProfile> activeProfiles) {
    }

    private class EntityMetadataListener implements PacketListener {

        @Override
        public void onPacketSending(PacketEvent event) {
            PacketContainer packet = event.getPacket();
            if (packet.getType() != PacketType.Play.Server.ENTITY_METADATA) {
                return;
            }

            final int entityId = packet.getIntegers().read(0);

            final List<WrappedWatchableObject> metadata = packet.getWatchableCollectionModifier().read(0);
            if (metadata == null || metadata.isEmpty()) {
                return;
            }

            final Player player = event.getPlayer();
            final Entity entity = player.getWorld().getEntities().parallelStream()
                    .filter(entities -> entities.getEntityId() == entityId)
                    .findAny()
                    .orElse(null);

            if (entity == null) {
                return;
            }

            if (!CitizensAPI.getNPCRegistry().isNPC(entity)) {
                return;
            }

            final NPC npc = CitizensAPI.getNPCRegistry().getNPC(entity);
            if (npc == null || !npc.isSpawned()) {
                return;
            }

            if (!isGlowing(npc, PlayerConverter.getID(player))) {
                return;
            }

            final WrappedWatchableObject watchableObject = metadata.get(0);
            final Object dataWatcherValue = watchableObject.getValue();
            if (!(dataWatcherValue instanceof Byte)) {
                return;
            }
            byte entityByte = (byte) dataWatcherValue;
            entityByte = (byte) (entityByte | 0x40);
            watchableObject.setValue(entityByte);
        }

        @Override
        public void onPacketReceiving(PacketEvent event) {
        }

        @Override
        public ListeningWhitelist getSendingWhitelist() {
            return ListeningWhitelist.newBuilder()
                    .priority(ListenerPriority.NORMAL)
                    .types(PacketType.Play.Server.ENTITY_METADATA)
                    .gamePhase(GamePhase.PLAYING)
                    .build();
        }

        @Override
        public ListeningWhitelist getReceivingWhitelist() {
            return ListeningWhitelist.newBuilder().build();
        }

        @Override
        public Plugin getPlugin() {
            return BetonQuest.getInstance();
        }
    }

}
