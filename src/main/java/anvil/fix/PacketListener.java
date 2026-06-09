package anvil.fix;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.simple.PacketPlaySendEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetExperience;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowProperty;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static anvil.fix.Events.blockedPreparing;
import static anvil.fix.Events.maximumRepairCosts;
import static anvil.fix.Events.preparing;

public final class PacketListener extends PacketListenerAbstract {

    private static boolean debugOutput;
    private static boolean blockedCostChat;
    private static final Map<UUID, Long> lastBlockedChat = new ConcurrentHashMap<>();
    private static final Map<UUID, String> lastBlockedChatKey = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> lastBlockedRawCost = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> lastBlockedAdjustedCost = new ConcurrentHashMap<>();

    public static void configure(boolean debugOutput, boolean blockedCostChat) {
        PacketListener.debugOutput = debugOutput;
        PacketListener.blockedCostChat = blockedCostChat;
    }

    public static void clearBlockedCostChat(UUID uuid) {
        lastBlockedChat.remove(uuid);
        lastBlockedChatKey.remove(uuid);
        lastBlockedRawCost.remove(uuid);
        lastBlockedAdjustedCost.remove(uuid);
    }

    @Override
    public void onPacketSend(PacketSendEvent _event) {
        if (!(_event instanceof PacketPlaySendEvent event)) return;

        UUID uuid = event.getUser().getUUID();

        switch (event.getPacketType()) {
            case PLAYER_ABILITIES -> {
                if (preparing.containsKey(uuid)) {
                    WrapperPlayServerPlayerAbilities wrapper = new WrapperPlayServerPlayerAbilities(event);
                    debug("PLAYER_ABILITIES uuid=" + uuid + " preparingLevel=" + preparing.get(uuid) + " blocked=" + blockedPreparing.contains(uuid) + " originalCreative=" + wrapper.isInCreativeMode() + " -> creative=true");
                    wrapper.setInCreativeMode(true);
                    event.markForReEncode(true);
                }
            }
            case SET_EXPERIENCE -> {
                Integer level = preparing.get(uuid);
                if (level == null) return;

                WrapperPlayServerSetExperience wrapper = new WrapperPlayServerSetExperience(event);
                boolean blocked = blockedPreparing.contains(uuid);
                debug("SET_EXPERIENCE uuid=" + uuid + " preparingLevel=" + level + " blocked=" + blocked + " packetLevel=" + wrapper.getLevel() + " packetTotalExp=" + wrapper.getTotalExperience() + " packetExpBar=" + wrapper.getExperienceBar());
                if (blocked) {
                    debug("SET_EXPERIENCE no rewrite uuid=" + uuid + " because blocked-cost display preserves the player's real level.");
                    return;
                }

                if (wrapper.getLevel() < level) {
                    int originalLevel = wrapper.getLevel();
                    wrapper.setLevel(level);
                    debug("SET_EXPERIENCE rewrite uuid=" + uuid + " level " + originalLevel + " -> " + wrapper.getLevel() + " so client can render cost " + level + ".");
                    event.markForReEncode(true);
                } else {
                    debug("SET_EXPERIENCE no rewrite uuid=" + uuid + " because packetLevel already covers displayed cost.");
                }
            }
            case WINDOW_PROPERTY -> {
                Integer level = preparing.get(uuid);
                if (level == null) return;

                WrapperPlayServerWindowProperty wrapper = new WrapperPlayServerWindowProperty(event);
                debug("WINDOW_PROPERTY uuid=" + uuid + " id=" + wrapper.getId() + " value=" + wrapper.getValue() + " preparingLevel=" + level + " blocked=" + blockedPreparing.contains(uuid));
                if (wrapper.getId() != 0) {
                    debug("WINDOW_PROPERTY ignored uuid=" + uuid + " because property id is not repair cost.");
                    return;
                }
                if (wrapper.getValue() != level) {
                    debug("WINDOW_PROPERTY raw repair cost differs from observed final cost for uuid=" + uuid + ": raw=" + wrapper.getValue() + ", observed=" + level + ", blocked=" + blockedPreparing.contains(uuid) + ".");
                }
                sendBlockedCostChat(uuid, wrapper.getValue(), level);
                wrapper.setValue(level);
                debug("WINDOW_PROPERTY rewrite uuid=" + uuid + " repair cost -> " + level + ".");
                event.markForReEncode(true);
            }
            case SET_SLOT -> {
                Integer level = preparing.get(uuid);
                if (level == null) return;

                WrapperPlayServerSetSlot wrapper = new WrapperPlayServerSetSlot(event);
                debug("SET_SLOT uuid=" + uuid + " windowId=" + wrapper.getWindowId() + " slot=" + wrapper.getSlot() + " preparingLevel=" + level + " blocked=" + blockedPreparing.contains(uuid) + "; scheduling repair-cost resend.");
                // Result-slot updates can race the repair-cost property packet,
                // so resend the cost immediately after the slot packet is sent.
                event.getPostTasks().add(() -> {
                    debug("SET_SLOT post-task uuid=" + uuid + " windowId=" + wrapper.getWindowId() + " sending repair-cost property=" + level + ".");
                    event.getUser().sendPacket(new WrapperPlayServerWindowProperty((byte) wrapper.getWindowId(), 0, level));
                });

            }
        }
    }

    public static WrapperPlayServerPlayerAbilities createExact(Player player) {
        return create(player, player.getGameMode() == GameMode.CREATIVE);
    }

    public static WrapperPlayServerPlayerAbilities create(Player player, boolean creative) {
        return new WrapperPlayServerPlayerAbilities(player.isInvulnerable(), player.isFlying(), player.getAllowFlight(), creative, player.getFlySpeed() / 2, player.getWalkSpeed() / 2);
    }

    public static WrapperPlayServerSetExperience createExactExperience(Player player) {
        return createExperience(player, player.getLevel());
    }

    public static WrapperPlayServerSetExperience createExperience(Player player, int level) {
        return new WrapperPlayServerSetExperience(player.getExp(), Math.max(player.getLevel(), level), player.getTotalExperience());
    }

    public static void init() {
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListener());
    }

    private static void debug(String message) {
        if (debugOutput) {
            Main.instance.getLogger().info("[debug] [packet] " + message);
        }
    }

    private static void sendBlockedCostChat(UUID uuid, int rawCost, int adjustedCost) {
        if (!blockedCostChat || !blockedPreparing.contains(uuid)) return;

        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;

        int cap = maximumRepairCosts.getOrDefault(uuid, 0);
        // AuraSkills may send the discounted spend cost after exposing the
        // undiscounted base level. Remember both so the chat can explain why an
        // apparently affordable discounted cost was still blocked by the cap.
        if (rawCost > adjustedCost) {
            lastBlockedRawCost.put(uuid, rawCost);
            lastBlockedAdjustedCost.put(uuid, adjustedCost);
        }

        Integer rememberedAdjustedCost = lastBlockedAdjustedCost.get(uuid);
        int effectiveRawCost = rememberedAdjustedCost != null && rememberedAdjustedCost == adjustedCost
            ? lastBlockedRawCost.getOrDefault(uuid, rawCost)
            : rawCost;
        String key = effectiveRawCost + "|" + adjustedCost + "|" + cap;
        long now = System.currentTimeMillis();
        Long lastSent = lastBlockedChat.get(uuid);
        String lastKey = lastBlockedChatKey.get(uuid);
        boolean recent = lastSent != null && now - lastSent < 10_000L;
        boolean hasBaseCostDetail = effectiveRawCost > adjustedCost;
        // Avoid chat spam while still allowing a later packet with base-cost
        // detail to replace an earlier generic blocked message.
        if (recent && (key.equals(lastKey) || !hasBaseCostDetail)) return;

        lastBlockedChat.put(uuid, now);
        lastBlockedChatKey.put(uuid, key);

        boolean baseOverCap = cap > 0 && effectiveRawCost > cap;
        boolean lacksLevels = player.getLevel() < adjustedCost;

        if (baseOverCap && lacksLevels) {
            player.sendMessage("Anvil blocked: base level " + effectiveRawCost + " > cap " + cap + ". You also need " + adjustedCost + " XP levels after Wisdom.");
            debug("Sent blocked-cost chat uuid=" + uuid + " rawCost=" + effectiveRawCost + " adjustedCost=" + adjustedCost + " cap=" + cap + " playerLevel=" + player.getLevel() + ".");
            return;
        }

        if (baseOverCap) {
            player.sendMessage("Anvil blocked: base level " + effectiveRawCost + " > cap " + cap + ". Wisdom would lower the cost to " + adjustedCost + " if allowed.");
            debug("Sent blocked-cost chat uuid=" + uuid + " rawCost=" + effectiveRawCost + " adjustedCost=" + adjustedCost + " cap=" + cap + " playerLevel=" + player.getLevel() + ".");
            return;
        }

        if (lacksLevels) {
            player.sendMessage("Anvil blocked: you need " + adjustedCost + " XP levels after Wisdom.");
            debug("Sent low-level blocked-cost chat uuid=" + uuid + " rawCost=" + effectiveRawCost + " adjustedCost=" + adjustedCost + " cap=" + cap + " playerLevel=" + player.getLevel() + ".");
            return;
        }

        if (cap > 0) {
            player.sendMessage("Anvil blocked: cost " + adjustedCost + ", cap " + cap + ". Wisdom only discounts allowed enchants.");
            debug("Sent generic blocked-cost chat uuid=" + uuid + " rawCost=" + rawCost + " adjustedCost=" + adjustedCost + " cap=" + cap + ".");
        }
    }
}
