package com.maks.playerdataplugin;

import com.maks.playerdataplugin.DatabaseManager;
import com.maks.playerdataplugin.PlayerDataListener;
import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin {

    private static Main instance;
    private DatabaseManager databaseManager;

    @Override
    public void onEnable() {
        instance = this;

        // Disable saving player data to disk
        getServer().getWorlds().forEach(world -> world.setAutoSave(false));

        // Initialize the database manager
        databaseManager = new DatabaseManager(this);
        databaseManager.connect();

        // Register events
        getServer().getPluginManager().registerEvents(new PlayerDataListener(this), this);

        // Schedule periodic saving every 5 minutes (6000 ticks)
        getServer().getScheduler().runTaskTimer(this, () -> {
            getServer().getOnlinePlayers().forEach(player -> {
                new PlayerDataListener(this).savePlayerData(player.getUniqueId(), player.getInventory());
            });
        }, 6000L, 6000L);

        // Save default config if needed
        saveDefaultConfig();
    }

    @Override
    public void onDisable() {
        // Save all online players' data
        getServer().getOnlinePlayers().forEach(player -> {
            new PlayerDataListener(this).savePlayerData(player.getUniqueId(), player.getInventory());
        });

        // Disconnect from the database
        databaseManager.disconnect();
    }

    public static Main getInstance() {
        return instance;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
}
