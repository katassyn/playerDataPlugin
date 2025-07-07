package com.maks.playerdataplugin;

import com.maks.playerdataplugin.DatabaseManager;
import com.maks.playerdataplugin.PlayerDataListener;
import com.maks.playerdataplugin.PlayerStatsManager;
import com.maks.playerdataplugin.PlayerStatsListener;
import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin {

    private static Main instance;
    private DatabaseManager databaseManager;
    private PlayerDataListener playerDataListener;
    private PlayerStatsManager playerStatsManager;
    private PlayerStatsListener playerStatsListener;

    @Override
    public void onEnable() {
        instance = this;

        // Disable saving player data to disk
        getServer().getWorlds().forEach(world -> world.setAutoSave(false));

        // Save default config if needed
        saveDefaultConfig();

        // Initialize the database manager
        databaseManager = new DatabaseManager(this);
        databaseManager.connect();

        // Initialize stats manager
        try {
            playerStatsManager = new PlayerStatsManager(this);
            playerStatsManager.createStatsTable();
            getLogger().info("Player stats system initialized.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize player stats system: " + e.getMessage());
            e.printStackTrace();
        }

        // Register event listeners
        playerDataListener = new PlayerDataListener(this);
        getServer().getPluginManager().registerEvents(playerDataListener, this);

        if (playerStatsManager != null) {
            playerStatsListener = new PlayerStatsListener(this, playerStatsManager);
            getServer().getPluginManager().registerEvents(playerStatsListener, this);

            // Register commands
            StatsCommand statsCommand = new StatsCommand(this, playerStatsManager);
            getCommand("stats").setExecutor(statsCommand);
            getCommand("stats").setTabCompleter(statsCommand);
        }

        // Get save interval and batch size from config
        long saveIntervalTicks = getConfig().getLong("saveInterval.ticks", 1200L); // Default: 1 minute (1200 ticks)
        int batchSizePercent = getConfig().getInt("saveInterval.batchSizePercent", 20); // Default: 20% of players per batch

        // Get stats save interval from config
        long statsSaveIntervalTicks = getConfig().getLong("statsInterval.ticks", 6000L); // Default: 5 minutes (6000 ticks)

        if (getConfig().getBoolean("debug", false)) {
            getLogger().info("[DEBUG] Scheduled saving with interval: " + saveIntervalTicks + " ticks, batch size: " + batchSizePercent + "%");
            getLogger().info("[DEBUG] Stats saving interval: " + statsSaveIntervalTicks + " ticks");
        }

        // Schedule staggered periodic saving for inventory data
        final int[] saveIndex = {0};
        getServer().getScheduler().runTaskTimer(this, () -> {
            java.util.List<org.bukkit.entity.Player> players = new java.util.ArrayList<>(getServer().getOnlinePlayers());
            if (players.isEmpty()) return;

            // Calculate how many players to save in this batch
            int playersPerBatch = Math.max(1, (int)(players.size() * (batchSizePercent / 100.0)));
            int start = saveIndex[0];
            int end = Math.min(start + playersPerBatch, players.size());

            if (getConfig().getBoolean("debug", false)) {
                getLogger().info("[DEBUG] Saving players " + start + " to " + (end-1) + " of " + players.size() + 
                                " (batch size: " + playersPerBatch + ")");
            }

            for (int i = start; i < end; i++) {
                org.bukkit.entity.Player player = players.get(i);
                playerDataListener.savePlayerData(player.getUniqueId(), player.getInventory());
            }

            saveIndex[0] = (end >= players.size()) ? 0 : end;
        }, saveIntervalTicks, saveIntervalTicks);

        // Schedule periodic stats saving
        if (playerStatsListener != null) {
            getServer().getScheduler().runTaskTimer(this, () -> {
                if (getConfig().getBoolean("debug", false)) {
                    getLogger().info("[DEBUG] Running periodic stats save for all online players");
                }
                playerStatsListener.saveAllOnlinePlayersStats();
            }, statsSaveIntervalTicks, statsSaveIntervalTicks);
        }

        // Load stats for players who are already online (in case of reload)
        if (playerStatsManager != null) {
            getServer().getOnlinePlayers().forEach(player -> {
                playerStatsManager.loadPlayerStats(player.getUniqueId());
                playerStatsManager.startPlaytimeTracking(player.getUniqueId());
            });
        }
    }

    @Override
    public void onDisable() {
        // Save all online players' inventory data
        getServer().getOnlinePlayers().forEach(player -> {
            playerDataListener.savePlayerData(player.getUniqueId(), player.getInventory());
        });

        // Save all online players' stats and stop playtime tracking
        if (playerStatsManager != null) {
            getServer().getOnlinePlayers().forEach(player -> {
                playerStatsManager.stopPlaytimeTracking(player.getUniqueId());
                playerStatsManager.savePlayerStats(player.getUniqueId());
            });
        }

        // Disconnect from the database
        databaseManager.disconnect();
    }

    public static Main getInstance() {
        return instance;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public PlayerStatsManager getPlayerStatsManager() {
        return playerStatsManager;
    }
}
