package anvil.fix;

import com.github.retrooper.packetevents.PacketEvents;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class Events implements Listener {

    public static final Map<UUID, Integer> preparing = new ConcurrentHashMap<>();
    public static final Map<UUID, Integer> maximumRepairCosts = new ConcurrentHashMap<>();
    public static final Set<UUID> blockedPreparing = ConcurrentHashMap.newKeySet();

    private static final AtomicLong prepareEvents = new AtomicLong();
    private static final Map<UUID, Long> lastBlockedActionbar = new ConcurrentHashMap<>();

    private final boolean uiOnlyMode;
    private final boolean debugOutput;
    private final boolean showBlockedCost;
    private final boolean blockedCostActionbar;

    public Events(boolean uiOnlyMode, boolean debugOutput, boolean showBlockedCost, boolean blockedCostActionbar) {
        this.uiOnlyMode = uiOnlyMode;
        this.debugOutput = debugOutput;
        this.showBlockedCost = showBlockedCost;
        this.blockedCostActionbar = blockedCostActionbar;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        long debugId = prepareEvents.incrementAndGet();
        if (uiOnlyMode) {
            this.debug(debugId, "Skipping default handler because ui-only-mode is enabled.");
            return;
        }

        if (!(event.getView().getPlayer() instanceof Player player)) return;

        AnvilInventory inv = event.getInventory();
        this.debugAnvilState(debugId, "DEFAULT/HIGH start", player, inv, event.getResult());

        inv.setMaximumRepairCost(Integer.MAX_VALUE - 10);
        this.debug(debugId, "Set maximum repair cost to " + inv.getMaximumRepairCost() + " for default Modern-NotTooExpensive handling.");

        // The vanilla client only replaces the cost with "Too Expensive!" at 40+ levels.
        if (inv.getRepairCost() >= 0 && inv.getRepairCost() <= 39) {
            this.debug(debugId, "Cost " + inv.getRepairCost() + " is below client too-expensive threshold. Removing spoof state.");
            removeSpoofState(player, debugOutput);
            return;
        }

        ItemStack combineWith = inv.getItem(1);
        if (combineWith == null || combineWith.getType().isAir()) {
            this.debug(debugId, "No second input item. Leaving event unchanged.");
            return;
        }

        Material combineType = combineWith.getType();

        ItemStack input = inv.getItem(0);
        if (input == null) {
            this.debug(debugId, "No first input item. Leaving event unchanged.");
            return;
        }

        //Integer level = preparing.get(player.getUniqueId());

        Material inputType = input.getType();

        if (combineType != Material.ENCHANTED_BOOK && combineType != Material.BOOK && combineType != inputType && inv.getItem(2) == null) {
            this.debug(debugId, "Second input is not a book or matching item and no vanilla result exists. Leaving event unchanged.");
            return;
        }

        ItemStack result = input.clone();//already done with "asMirrorCopy", but it's best to be safe
        Map<Enchantment, Integer> enchantsOnInput = input.getEnchantments();

        var meta = combineWith.getItemMeta();
        assert meta != null;
        Map<Enchantment, Integer> enchants = meta instanceof EnchantmentStorageMeta e ? e.getStoredEnchants() : meta.getEnchants();
        Set<Map.Entry<Enchantment, Integer>> enchantsOnBook = enchants.entrySet();

        float cost = this.calculateInitialCost(enchantsOnInput);
        this.debug(debugId, "Custom formula initial cost=" + cost + " from input enchants=" + describeEnchantments(enchantsOnInput));
        int applied = 0;

        for (Map.Entry<Enchantment, Integer> e : enchantsOnBook) {
            Enchantment enchantment = e.getKey();
            int level = e.getValue();
            int existingLevel = result.getEnchantmentLevel(enchantment);
            boolean conflicts = this.hasConflicting(enchantsOnInput, enchantment);
            boolean canEnchant = enchantment.canEnchantItem(input);
            boolean shouldApply = existingLevel < level && !conflicts && canEnchant;
            this.debug(debugId, "Considering enchant " + describeEnchantment(enchantment) + " inputLevel=" + existingLevel + " offeredLevel=" + level + " conflicts=" + conflicts + " canEnchant=" + canEnchant + " apply=" + shouldApply);
            if (shouldApply) {
                result.addUnsafeEnchantment(enchantment, level);
                applied++;
                cost += level * 1.5f;
                this.debug(debugId, "Applied " + describeEnchantment(enchantment) + " level=" + level + "; cost now " + cost + ".");
            }
        }

        if (applied == 0) {
            this.debug(debugId, "No enchantments were applied by custom formula. Leaving event unchanged.");
            return;
        }

        String rename = inv.getRenameText();
        if (rename != null && !rename.isEmpty()) {
            cost++;
            this.debug(debugId, "Rename text '" + rename + "' adds 1 level; cost now " + cost + ".");
            ItemMeta resultMeta = result.getItemMeta();
            assert resultMeta != null;//pretty much useless
            resultMeta.setDisplayName(rename);
            result.setItemMeta(resultMeta);
        }

        event.setResult(result);
        inv.setRepairCost((int) cost);
        preparing.put(player.getUniqueId(), (int) cost);
        blockedPreparing.remove(player.getUniqueId());
        this.debug(debugId, "Set custom result=" + describeItem(result) + "; repairCost=" + inv.getRepairCost() + "; preparing[" + player.getUniqueId() + "]=" + (int) cost + ".");
        this.sendAnvilSpoof(player, (int) cost);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnvilPrepareUiOnly(PrepareAnvilEvent event) {
        long debugId = prepareEvents.incrementAndGet();
        if (!uiOnlyMode) {
            this.debug(debugId, "Skipping UI-only handler because ui-only-mode is disabled.");
            return;
        }
        if (!(event.getView().getPlayer() instanceof Player player)) return;

        AnvilInventory inv = event.getInventory();
        int cost = inv.getRepairCost();
        ItemStack result = event.getResult();

        // UI-only mode runs at MONITOR so other plugins get the last word on
        // result generation, repair cost, conflicts, and whether the operation
        // is blocked. From this point on we only record state for packet display.
        this.debugAnvilState(debugId, "UI_ONLY/MONITOR observed final server state", player, inv, result);
        if (cost < 40) {
            this.debug(debugId, "UI-only will not spoof. result=" + describeItem(result) + " cost=" + cost + " reason=cost below threshold.");
            removeSpoofState(player, debugOutput);
            return;
        }

        if (result == null || result.getType().isAir()) {
            if (!showBlockedCost) {
                this.debug(debugId, "UI-only will not spoof blocked cost because show-blocked-cost is disabled. result=" + describeItem(result) + " cost=" + cost + ".");
                removeSpoofState(player, debugOutput);
                return;
            }

            // Some plugins expose the cost that failed their checks while
            // returning no output item. Spoofing this state must never create a
            // result; it only lets the client show a useful number.
            preparing.put(player.getUniqueId(), cost);
            maximumRepairCosts.put(player.getUniqueId(), inv.getMaximumRepairCost());
            blockedPreparing.add(player.getUniqueId());
            this.debug(debugId, "UI-only recorded blocked high-cost operation without mutation. No result exists, but preparing[" + player.getUniqueId() + "]=" + cost + " for display only.");
            this.sendBlockedAnvilSpoof(player, cost);
            this.sendBlockedCostActionbar(player, cost);
            return;
        }

        preparing.put(player.getUniqueId(), cost);
        maximumRepairCosts.put(player.getUniqueId(), inv.getMaximumRepairCost());
        if (player.getLevel() < cost) {
            blockedPreparing.add(player.getUniqueId());
            this.debug(debugId, "UI-only recorded existing server result, but player level " + player.getLevel() + " is below cost " + cost + ". Preserving real XP display.");
            this.sendBlockedAnvilSpoof(player, cost);
            return;
        }

        blockedPreparing.remove(player.getUniqueId());
        this.debug(debugId, "UI-only recorded existing server result without mutation. preparing[" + player.getUniqueId() + "]=" + cost + ".");
        this.sendAnvilSpoof(player, cost);
    }

    private float calculateInitialCost(Map<Enchantment, Integer> inputEnchants) {
        float cost = 40;
        for (Map.Entry<Enchantment, Integer> e : inputEnchants.entrySet()) {
            cost += e.getValue() / 2.5f;
        }
        return cost;
    }

    private boolean hasConflicting(Map<Enchantment, Integer> enchants, Enchantment toCheckConflict) {
        for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
            if (e.getKey().conflictsWith(toCheckConflict)) return true;
        }
        return false;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (event.getView().getTopInventory().getType() == InventoryType.ANVIL) {
            this.debug("InventoryCloseEvent for anvil by " + describePlayer(player) + ". Removing spoof state.");
            removeSpoofState(player, debugOutput);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (isAnvilOpen(player)) {
            this.debug("PlayerQuitEvent with anvil open for " + describePlayer(player) + ". Closing inventory before logout.");
            player.closeInventory();
        }
        removeSpoofState(player, debugOutput);
    }

    public static boolean isAnvilOpen(Player player) {
        return player.getOpenInventory().getTopInventory().getType() == InventoryType.ANVIL;
    }

    public static void removeSpoofState(Player player, boolean debugOutput) {
        Integer removed = preparing.remove(player.getUniqueId());
        maximumRepairCosts.remove(player.getUniqueId());
        blockedPreparing.remove(player.getUniqueId());
        lastBlockedActionbar.remove(player.getUniqueId());
        PacketListener.clearBlockedCostChat(player.getUniqueId());
        if (removed != null) {
            debug(debugOutput, "Removed preparing[" + player.getUniqueId() + "]=" + removed + "; restoring abilities and experience for " + describePlayer(player) + ".");
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, PacketListener.createExact(player));
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, PacketListener.createExactExperience(player));
        } else {
            debug(debugOutput, "No spoof state to remove for " + describePlayer(player) + ".");
        }
    }

    private void sendAnvilSpoof(Player player, int cost) {
        this.debug("Sending anvil spoof packets to " + describePlayer(player) + ": creative=true, displayedLevel=" + cost + ", playerLevel=" + player.getLevel() + ", expBar=" + player.getExp() + ", totalExp=" + player.getTotalExperience() + ".");
        // The too-expensive text is client-side. Temporarily spoofing creative
        // ability and a high enough level lets the client render the numeric
        // repair cost while the server remains authoritative.
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, PacketListener.create(player, true));
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, PacketListener.createExperience(player, cost));
    }

    private void sendBlockedAnvilSpoof(Player player, int cost) {
        this.debug("Sending blocked-cost display packets to " + describePlayer(player) + ": creative=true, displayedLevel=" + cost + ", exact player experience preserved.");
        // For blocked operations, keep the player's real XP visible so the UI
        // does not imply that they have enough levels to complete the combine.
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, PacketListener.create(player, true));
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, PacketListener.createExactExperience(player));
    }

    private void sendBlockedCostActionbar(Player player, int cost) {
        if (!blockedCostActionbar) return;

        long now = System.currentTimeMillis();
        Long lastSent = lastBlockedActionbar.get(player.getUniqueId());
        if (lastSent != null && now - lastSent < 1500L) return;

        lastBlockedActionbar.put(player.getUniqueId(), now);
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText("Anvil blocked: server cost is " + cost + " levels, but no result is available."));
        this.debug("Sent blocked-cost actionbar to " + describePlayer(player) + " for cost=" + cost + ".");
    }

    private void debugAnvilState(long debugId, String phase, Player player, AnvilInventory inv, ItemStack eventResult) {
        this.debug(debugId, phase + " player=" + describePlayer(player)
            + " repairCost=" + inv.getRepairCost()
            + " repairCostAmount=" + inv.getRepairCostAmount()
            + " maximumRepairCost=" + inv.getMaximumRepairCost()
            + " rename='" + inv.getRenameText() + "'"
            + " input0=" + describeItem(inv.getItem(0))
            + " input1=" + describeItem(inv.getItem(1))
            + " resultSlot=" + describeItem(inv.getItem(2))
            + " eventResult=" + describeItem(eventResult)
            + " existingPreparing=" + preparing.get(player.getUniqueId()));
    }

    private void debug(long debugId, String message) {
        this.debug("[prepare#" + debugId + "] " + message);
    }

    private void debug(String message) {
        debug(debugOutput, message);
    }

    private static void debug(boolean debugOutput, String message) {
        if (debugOutput) {
            Main.instance.getLogger().info("[debug] " + message);
        }
    }

    private static String describePlayer(Player player) {
        return player.getName() + "/" + player.getUniqueId()
            + " level=" + player.getLevel()
            + " expBar=" + player.getExp()
            + " totalExp=" + player.getTotalExperience()
            + " gameMode=" + player.getGameMode();
    }

    private static String describeItem(ItemStack item) {
        if (item == null) return "null";
        if (item.getType().isAir()) return item.getType().name();

        String displayName = "";
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            displayName = ",name='" + meta.getDisplayName() + "'";
        }

        return item.getType().name()
            + "x" + item.getAmount()
            + displayName
            + ",enchants=" + describeEnchantments(item.getEnchantments())
            + ",stored=" + describeStoredEnchantments(item);
    }

    private static String describeStoredEnchantments(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storageMeta) {
            return describeEnchantments(storageMeta.getStoredEnchants());
        }
        return "{}";
    }

    private static String describeEnchantments(Map<Enchantment, Integer> enchantments) {
        if (enchantments == null || enchantments.isEmpty()) return "{}";

        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            if (!first) builder.append(", ");
            builder.append(describeEnchantment(entry.getKey())).append("=").append(entry.getValue());
            first = false;
        }
        return builder.append("}").toString();
    }

    private static String describeEnchantment(Enchantment enchantment) {
        return enchantment.getKey().toString();
    }
}
