package com.maks.playerdataplugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class Main extends JavaPlugin {

    private static Main instance;
    private DatabaseManager databaseManager;
    private PlayerDataListener playerDataListener;
    private PlayerStatsManager playerStatsManager;
    private PlayerStatsListener playerStatsListener;
    private FirstJoinKitManager firstJoinKitManager;
    private FirstJoinKitListener firstJoinKitListener;

    // Shutdown handling
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private ScheduledExecutorService monitorExecutor;
    private ScheduledExecutorService saveScheduler;

    // Crash detection
    private final AtomicLong lastHeartbeat = new AtomicLong(System.currentTimeMillis());
    private Thread heartbeatThread;
    private Thread emergencySaveThread;

    // Performance monitoring
    private final ConcurrentHashMap<String, Long> operationTimings = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        instance = this;

        // Register shutdown hook for emergency saves
        Runtime.getRuntime().addShutdownHook(new Thread(this::emergencyShutdown, "PlayerDataPlugin-Shutdown"));

        // Save default config if needed
        saveDefaultConfig();
        reloadConfig();

        // Initialize monitoring
        if (getConfig().getBoolean("monitoring.enabled", true)) {
            initializeMonitoring();
        }

        // Start heartbeat for crash detection
        if (getConfig().getBoolean("emergency.crashDetection", true)) {
            startHeartbeat();
        }

        // Disable vanilla saving to prevent conflicts
        getServer().getWorlds().forEach(world -> {
            world.setAutoSave(false);
            getLogger().info("Disabled auto-save for world: " + world.getName());
        });

        // Initialize the database manager
        try {
            databaseManager = new DatabaseManager(this);
            databaseManager.connect();

            if (!databaseManager.isConnected()) {
                getLogger().severe("Failed to connect to database! Plugin will be disabled.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        } catch (Exception e) {
            getLogger().severe("Critical error initializing database: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize managers
        initializeManagers();

        // Register event listeners
        registerListeners();

        // Register commands
        registerCommands();

        // Start scheduled tasks
        startScheduledTasks();

        // Load data for already online players (in case of reload)
        loadOnlinePlayersData();

        getLogger().info("PlayerDataPlugin has been enabled successfully with enhanced safety features!");
    }

    private void initializeManagers() {
        // Initialize first join kit manager
        firstJoinKitManager = new FirstJoinKitManager(this);

        // Initialize stats manager
        try {
            playerStatsManager = new PlayerStatsManager(this);
            playerStatsManager.createStatsTable();
            getLogger().info("Player stats system initialized.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize player stats system: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void registerListeners() {
        // Register player data listener
        playerDataListener = new PlayerDataListener(this, firstJoinKitManager);
        getServer().getPluginManager().registerEvents(playerDataListener, this);

        // Register first join kit listener
        firstJoinKitListener = new FirstJoinKitListener(firstJoinKitManager);
        getServer().getPluginManager().registerEvents(firstJoinKitListener, this);

        // Register stats listener
        if (playerStatsManager != null) {
            playerStatsListener = new PlayerStatsListener(this, playerStatsManager);
            getServer().getPluginManager().registerEvents(playerStatsListener, this);
        }
    }

    private void registerCommands() {
        // Register stats command
        if (playerStatsManager != null) {
            StatsCommand statsCommand = new StatsCommand(this, playerStatsManager);
            getCommand("stats").setExecutor(statsCommand);
            getCommand("stats").setTabCompleter(statsCommand);
        }

        // Register first join kit command
        FirstJoinKitCommand firstJoinKitCommand = new FirstJoinKitCommand(firstJoinKitManager);
        getCommand("firstjoinkit").setExecutor(firstJoinKitCommand);

        // Register raffle command
        RaffleCommand raffleCommand = new RaffleCommand(this);
        getCommand("raffle").setExecutor(raffleCommand);
    }

    private void startScheduledTasks() {
        saveScheduler = Executors.newScheduledThreadPool(2);

        // Get intervals from config
        long saveIntervalTicks = getConfig().getLong("saveInterval.ticks", 600L);
        int batchSizePercent = getConfig().getInt("saveInterval.batchSizePercent", 25);
        long statsSaveIntervalTicks = getConfig().getLong("statsInterval.ticks", 3000L);

        // Schedule inventory saves with improved batching
        final int[] saveIndex = {0};
        saveScheduler.scheduleWithFixedDelay(() -> {
            if (isShuttingDown.get()) return;

            try {
                updateHeartbeat();

                java.util.List<Player> players = new java.util.ArrayList<>(getServer().getOnlinePlayers());
                if (players.isEmpty()) return;

                int playersPerBatch = Math.max(1, (int)(players.size() * (batchSizePercent / 100.0)));
                int start = saveIndex[0];
                int end = Math.min(start + playersPerBatch, players.size());

                if (getConfig().getBoolean("debug", false)) {
                    getLogger().info("[DEBUG] Batch saving players " + start + " to " + (end-1) +
                            " of " + players.size());
                }

                for (int i = start; i < end; i++) {
                    Player player = players.get(i);
                    if (player.isOnline()) { // Double-check player is still online
                        playerDataListener.savePlayerData(player.getUniqueId(), player.getInventory());
                    }
                }

                saveIndex[0] = (end >= players.size()) ? 0 : end;

            } catch (Exception e) {
                getLogger().severe("Error in scheduled save task: " + e.getMessage());
                e.printStackTrace();
            }
        }, saveIntervalTicks / 20, saveIntervalTicks / 20, TimeUnit.SECONDS);

        // Schedule stats saves
        if (playerStatsListener != null) {
            saveScheduler.scheduleWithFixedDelay(() -> {
                if (isShuttingDown.get()) return;

                try {
                    updateHeartbeat();

                    if (getConfig().getBoolean("debug", false)) {
                        getLogger().info("[DEBUG] Running periodic stats save");
                    }
                    playerStatsListener.saveAllOnlinePlayersStats();

                } catch (Exception e) {
                    getLogger().severe("Error in stats save task: " + e.getMessage());
                    e.printStackTrace();
                }
            }, statsSaveIntervalTicks / 20, statsSaveIntervalTicks / 20, TimeUnit.SECONDS);
        }
    }

    private void loadOnlinePlayersData() {
        if (playerStatsManager != null) {
            for (Player player : getServer().getOnlinePlayers()) {
                playerStatsManager.loadPlayerStats(player.getUniqueId());
                playerStatsManager.startPlaytimeTracking(player.getUniqueId());
            }
        }
    }

    private void initializeMonitoring() {
        monitorExecutor = Executors.newSingleThreadScheduledExecutor();

        // Monitor for slow operations
        monitorExecutor.scheduleAtFixedRate(() -> {
            long threshold = getConfig().getLong("monitoring.slowOperationThreshold", 1000);

            operationTimings.forEach((operation, duration) -> {
                if (duration > threshold) {
                    getLogger().warning("Slow operation detected: " + operation +
                            " took " + duration + "ms");
                }
            });

            operationTimings.clear();
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void startHeartbeat() {
        heartbeatThread = new Thread(() -> {
            while (!isShuttingDown.get()) {
                try {
                    updateHeartbeat();
                    Thread.sleep(1000); // Update every second
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "PlayerDataPlugin-Heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();

        // Emergency save thread monitors heartbeat
        emergencySaveThread = new Thread(() -> {
            long freezeThreshold = getConfig().getLong("emergency.freezeThreshold", 30) * 1000;

            while (!isShuttingDown.get()) {
                try {
                    Thread.sleep(5000); // Check every 5 seconds

                    long lastBeat = lastHeartbeat.get();
                    long timeSinceLastBeat = System.currentTimeMillis() - lastBeat;

                    if (timeSinceLastBeat > freezeThreshold) {
                        getLogger().severe("Server freeze detected! Last heartbeat was " +
                                timeSinceLastBeat + "ms ago. Initiating emergency save!");
                        emergencySave("FREEZE_DETECTED");

                        // Wait before checking again to avoid spam
                        Thread.sleep(60000);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "PlayerDataPlugin-EmergencyMonitor");
        emergencySaveThread.setDaemon(true);
        emergencySaveThread.start();
    }

    private void updateHeartbeat() {
        lastHeartbeat.set(System.currentTimeMillis());
    }

    @Override
    public void onDisable() {
        if (isShuttingDown.getAndSet(true)) {
            getLogger().warning("Already shutting down, skipping duplicate shutdown");
            return;
        }

        getLogger().info("Starting graceful shutdown...");
        long shutdownStart = System.currentTimeMillis();

        try {
            // Stop accepting new operations
            getLogger().info("Stopping scheduled tasks...");
            if (saveScheduler != null) {
                saveScheduler.shutdown();
            }
            if (monitorExecutor != null) {
                monitorExecutor.shutdown();
            }

            // Save all online players' data with timeout
            getLogger().info("Saving all player data...");
            CompletableFuture<Void> savesFuture = saveAllPlayersAsync();

            try {
                savesFuture.get(10, TimeUnit.SECONDS);
                getLogger().info("All player data saved successfully!");
            } catch (TimeoutException e) {
                getLogger().severe("Timeout while saving player data! Some data may be lost!");
                emergencySave("SHUTDOWN_TIMEOUT");
            }

            // Shutdown listeners
            if (playerDataListener != null) {
                getLogger().info("Shutting down data listener...");
                playerDataListener.shutdown();
            }

            // Await termination of executors
            try {
                if (saveScheduler != null && !saveScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    saveScheduler.shutdownNow();
                }
                if (monitorExecutor != null && !monitorExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    monitorExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Disconnect from database
            if (databaseManager != null) {
                getLogger().info("Closing database connections...");
                databaseManager.disconnect();
            }

            long shutdownTime = System.currentTimeMillis() - shutdownStart;
            getLogger().info("Graceful shutdown completed in " + shutdownTime + "ms");

        } catch (Exception e) {
            getLogger().severe("Error during shutdown: " + e.getMessage());
            e.printStackTrace();
            emergencySave("SHUTDOWN_ERROR");
        } finally {
            shutdownLatch.countDown();
        }
    }

    private CompletableFuture<Void> saveAllPlayersAsync() {
        java.util.List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();

        for (Player player : getServer().getOnlinePlayers()) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    // Save inventory
                    if (playerDataListener != null) {
                        playerDataListener.savePlayerDataImmediate(
                                player.getUniqueId(),
                                player.getInventory()
                        );
                    }

                    // Save stats
                    if (playerStatsManager != null) {
                        playerStatsManager.stopPlaytimeTracking(player.getUniqueId());
                        playerStatsManager.savePlayerStats(player.getUniqueId());
                    }
                } catch (Exception e) {
                    getLogger().severe("Failed to save data for " + player.getName() + ": " + e.getMessage());
                }
            });
            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private void emergencySave(String reason) {
        getLogger().warning("=== EMERGENCY SAVE INITIATED: " + reason + " ===");

        try {
            for (Player player : getServer().getOnlinePlayers()) {
                try {
                    // Create emergency backup
                    String inventoryData = SerializationUtils.serializeItemStackArray(
                            player.getInventory().getContents()
                    );
                    String armorData = SerializationUtils.serializeItemStackArray(
                            player.getInventory().getArmorContents()
                    );

                    // Direct database write, bypass normal save pipeline
                    if (databaseManager != null && databaseManager.isConnected()) {
                        try (java.sql.Connection conn = databaseManager.getConnection();
                             java.sql.PreparedStatement stmt = conn.prepareStatement(
                                     "INSERT INTO player_data_backup (uuid, inventory, armor, reason) VALUES (?, ?, ?, ?)")) {

                            stmt.setString(1, player.getUniqueId().toString());
                            stmt.setString(2, inventoryData);
                            stmt.setString(3, armorData);
                            stmt.setString(4, "EMERGENCY_" + reason);
                            stmt.executeUpdate();

                            getLogger().info("Emergency backup created for " + player.getName());
                        }
                    }
                } catch (Exception e) {
                    getLogger().severe("Failed emergency save for " + player.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            getLogger().severe("Critical error during emergency save: " + e.getMessage());
        }

        getLogger().warning("=== EMERGENCY SAVE COMPLETED ===");
    }

    private void emergencyShutdown() {
        if (!isShuttingDown.getAndSet(true)) {
            getLogger().severe("=== EMERGENCY SHUTDOWN DETECTED ===");
            emergencySave("JVM_SHUTDOWN");

            try {
                // Wait for emergency save with timeout
                shutdownLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
    }

    public void recordOperationTime(String operation, long duration) {
        operationTimings.put(operation, duration);
    }

    public boolean isShuttingDown() {
        return isShuttingDown.get();
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

    public FirstJoinKitManager getFirstJoinKitManager() {
        return firstJoinKitManager;
    }
}