package anvil.fix;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.stream.Collectors;

@MainClass
public final class Main extends JavaPlugin {

    public static Main instance;
    private boolean debugOutput;

    @Override
    public void onLoad() {
        instance = this;
    }

    @Override
    public void onEnable() {
        if (!getServer().getPluginManager().isPluginEnabled("packetevents")) {
            this.getLogger().info("Missing dependency: packetevents! Download from https://www.spigotmc.org/resources/packetevents-api.80279/ in order to use this plugin.");
            this.setEnabled(false);
            return;
        }
        boolean hadConfig = new File(getDataFolder(), "config.yml").isFile();
        saveDefaultConfig();
        writeConfigTemplateIfMissingKeys(hadConfig);

        boolean uiOnlyMode = getConfig().getBoolean("ui-only-mode", false);
        debugOutput = getConfig().getBoolean("debug-output", false);
        boolean showBlockedCost = getConfig().getBoolean("show-blocked-cost", true);
        boolean blockedCostChat = getConfig().getBoolean("blocked-cost-chat", true);
        boolean blockedCostActionbar = getConfig().getBoolean("blocked-cost-actionbar", false);
        if (uiOnlyMode) {
            getLogger().info("UI-only compatibility mode is enabled. Existing anvil result and cost logic will be respected.");
        }
        if (debugOutput) {
            getLogger().info("Debug output is enabled. Anvil and packet logs will be verbose.");
        }

        PacketListener.configure(debugOutput, blockedCostChat);
        this.getServer().getPluginManager().registerEvents(new Events(uiOnlyMode, debugOutput, showBlockedCost, blockedCostActionbar), this);
        PacketListener.init();
    }

    @Override
    public void onDisable() {
        for (Player player : getServer().getOnlinePlayers()) {
            if (Events.isAnvilOpen(player)) {
                // Packet spoofing is tied to an open anvil. Closing it during
                // disable prevents players from keeping a stale client state.
                getLogger().info("Closing open anvil inventory for " + player.getName() + " during plugin disable.");
                player.closeInventory();
            }
            Events.removeSpoofState(player, debugOutput);
        }
    }

    private void writeConfigTemplateIfMissingKeys(boolean hadConfig) {
        if (!hadConfig) return;

        File configFile = new File(getDataFolder(), "config.yml");
        try (InputStream defaultConfigStream = getResource("config.yml")) {
            if (defaultConfigStream == null) {
                getLogger().warning("Could not find bundled config.yml to compare against existing config.");
                return;
            }

            YamlConfiguration currentConfig = YamlConfiguration.loadConfiguration(configFile);
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultConfigStream, StandardCharsets.UTF_8));
            Set<String> missingKeys = defaultConfig.getKeys(true).stream()
                .filter(key -> !currentConfig.contains(key))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            if (missingKeys.isEmpty()) return;

            // Bukkit does not merge new defaults into an existing config file.
            // Write a sidecar template so admins can opt into new settings
            // without losing their current config.
            try (InputStream templateStream = getResource("config.yml")) {
                if (templateStream == null) {
                    getLogger().warning("Could not write config.yml.new because bundled config.yml was not found.");
                    return;
                }

                File newConfigFile = new File(getDataFolder(), "config.yml.new");
                Files.copy(templateStream, newConfigFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                getLogger().warning("Existing config.yml is missing keys " + missingKeys + ". Wrote config.yml.new with the latest defaults for manual merging.");
            }
        } catch (IOException e) {
            getLogger().warning("Could not write config.yml.new: " + e.getMessage());
        }
    }
}
