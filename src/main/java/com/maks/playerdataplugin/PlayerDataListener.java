package com.maks.playerdataplugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class PlayerDataListener implements Listener {

    private final Main plugin;
    private final FirstJoinKitManager kitManager;

    // Thread-safe collections
    private final ConcurrentHashMap<UUID, ReentrantLock> playerLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicBoolean> savingPlayers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlayerDataCache> dataCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> pendingSaves = new ConcurrentHashMap<>();

    // Executor for async operations
    private final ScheduledExecutorService saveExecutor = Executors.newScheduledThreadPool(4);
    private final ExecutorService loadExecutor = Executors.newCachedThreadPool();

    private boolean debugMode = false;
    private int maxRetryAttempts = 5;
    private long retryDelayMs = 1000;

    // Data integrity tracking
    private final ConcurrentHashMap<UUID, String> lastKnownChecksum = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> dataVersion = new ConcurrentHashMap<>();

    private static class PlayerDataCache {
        final String inventoryData;
        final String armorData;
        final String checksum;
        final long timestamp;
        final int version;
        volatile boolean isDirty;

        PlayerDataCache(String inventoryData, String armorData, String checksum, int version) {
            this.inventoryData = inventoryData;
            this.armorData = armorData;
            this.checksum = checksum;
            this.timestamp = System.currentTimeMillis();
            this.version = version;
            this.isDirty = false;
        }
    }

    public PlayerDataListener(Main plugin, FirstJoinKitManager kitManager) {
        this.plugin = plugin;
        this.kitManager = kitManager;
        this.debugMode = plugin.getConfig().getBoolean("debug", false);
        this.maxRetryAttempts = plugin.getConfig().getInt("database.maxRetryAttempts", 5);
        this.retryDelayMs = plugin.getConfig().getLong("database.retryDelayMs", 1000);
    }

    private void logDebug(String message) {
        if (debugMode) {
            plugin.getLogger().info("[DEBUG] " + message);
        }
    }

    private String calculateChecksum(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to calculate checksum: " + e.getMessage());
            return "";
        }
    }

    @EventHandler(priority = EventPriority.LOWEST) // Changed to LOWEST to allow other plugins to modify inventory after loading
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();

        // Get or create player lock
        ReentrantLock lock = playerLocks.computeIfAbsent(uuid, k -> new ReentrantLock());

        // Lock to prevent concurrent modifications
        lock.lock();
        try {
            logDebug("Player " + playerName + " joining, acquiring lock");

            // Cancel any pending saves
            ScheduledFuture<?> pendingSave = pendingSaves.remove(uuid);
            if (pendingSave != null) {
                pendingSave.cancel(false);
                logDebug("Cancelled pending save for " + playerName);
            }

            // Clear inventory first to prevent any issues
            PlayerInventory inventory = player.getInventory();
            inventory.clear();
            inventory.setArmorContents(null);

            // Load data asynchronously but wait for completion
            CompletableFuture<Boolean> loadFuture = loadPlayerDataAsync(player);

            try {
                boolean loaded = loadFuture.get(5, TimeUnit.SECONDS);
                if (!loaded) {
                    plugin.getLogger().warning("Failed to load data for " + playerName);
                    // Give starter kit as fallback
                    kitManager.giveKitIfFirstJoin(player);
                }
            } catch (TimeoutException e) {
                plugin.getLogger().severe("Timeout loading data for " + playerName);
                kitManager.giveKitIfFirstJoin(player);
            } catch (Exception e) {
                plugin.getLogger().severe("Error loading data for " + playerName + ": " + e.getMessage());
                kitManager.giveKitIfFirstJoin(player);
            }

        } finally {
            lock.unlock();
        }
    }

    private CompletableFuture<Boolean> loadPlayerDataAsync(Player player) {
        return CompletableFuture.supplyAsync(() -> {
            UUID uuid = player.getUniqueId();
            String playerName = player.getName();

            // Check cache first
            PlayerDataCache cached = dataCache.get(uuid);
            if (cached != null && !cached.isDirty) {
                long age = System.currentTimeMillis() - cached.timestamp;
                if (age < 30000) { // Cache valid for 30 seconds
                    logDebug("Using cached data for " + playerName + " (age: " + age + "ms)");
                    return applyDataToPlayer(player, cached);
                }
            }

            // Load from database
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT inventory, armor, checksum, version FROM player_data_info WHERE uuid=?")) {

                stmt.setString(1, uuid.toString());

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String inventoryData = rs.getString("inventory");
                        String armorData = rs.getString("armor");
                        String checksum = rs.getString("checksum");
                        int version = rs.getInt("version");

                        // Verify data integrity
                        String calculatedChecksum = calculateChecksum(inventoryData + armorData);
                        if (checksum != null && !checksum.equals(calculatedChecksum)) {
                            plugin.getLogger().warning("Checksum mismatch for " + playerName + ", attempting recovery");
                            return recoverFromBackup(player);
                        }

                        // Cache the data
                        PlayerDataCache newCache = new PlayerDataCache(inventoryData, armorData, checksum, version);
                        dataCache.put(uuid, newCache);
                        dataVersion.put(uuid, version);
                        lastKnownChecksum.put(uuid, checksum);

                        return applyDataToPlayer(player, newCache);
                    } else {
                        logDebug("No data found for new player " + playerName);
                        // New player - give kit
                        Bukkit.getScheduler().runTask(plugin, () -> kitManager.giveKitIfFirstJoin(player));
                        return true;
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to load data for " + playerName + ": " + e.getMessage());
                e.printStackTrace();
                return false;
            }
        }, loadExecutor);
    }

    private boolean applyDataToPlayer(Player player, PlayerDataCache cache) {
        if (cache == null || cache.inventoryData == null) {
            return false;
        }

        try {
            PlayerInventory inventory = player.getInventory();
            
            // Zachowaj menu item przed załadowaniem
            ItemStack menuItem = inventory.getItem(17);

            // Apply inventory
            if (!cache.inventoryData.isEmpty()) {
                ItemStack[] items = SerializationUtils.deserializeItemStackArray(cache.inventoryData);
                validateItems(items);
                
                // Nie nadpisuj slotu 17 (menu slot)
                if (items.length > 17) {
                    items[17] = null; // Pozostaw slot 17 pusty
                }

                // Apply on main thread
                Bukkit.getScheduler().runTask(plugin, () -> {
                    inventory.setContents(items);
                    // Przywróć menu item jeśli był
                    if (menuItem != null) {
                        inventory.setItem(17, menuItem);
                    }
                });
            }

            // Apply armor
            if (cache.armorData != null && !cache.armorData.isEmpty()) {
                ItemStack[] armor = SerializationUtils.deserializeItemStackArray(cache.armorData);
                validateItems(armor);

                // Apply on main thread
                Bukkit.getScheduler().runTask(plugin, () -> {
                    inventory.setArmorContents(armor);
                });
            }

            logDebug("Successfully applied data to player " + player.getName());
            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to apply data to player: " + e.getMessage());
            return false;
        }
    }

    private boolean recoverFromBackup(Player player) {
        UUID uuid = player.getUniqueId();

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT inventory, armor FROM player_data_backup WHERE uuid=? ORDER BY backup_time DESC LIMIT 1")) {

            stmt.setString(1, uuid.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String inventoryData = rs.getString("inventory");
                    String armorData = rs.getString("armor");

                    PlayerDataCache recoveredCache = new PlayerDataCache(
                            inventoryData, armorData, "", 0
                    );

                    plugin.getLogger().info("Recovered data from backup for " + player.getName());
                    return applyDataToPlayer(player, recoveredCache);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to recover from backup: " + e.getMessage());
        }

        return false;
    }

    private void validateItems(ItemStack[] items) {
        if (items == null) return;

        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item != null) {
                // Validate stack size
                if (item.getAmount() > item.getMaxStackSize()) {
                    item.setAmount(item.getMaxStackSize());
                }
                // Validate amount is positive
                if (item.getAmount() <= 0) {
                    items[i] = null;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST) // Lowest priority to ensure we're first
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Force immediate save on quit
        savePlayerDataImmediate(uuid, player.getInventory());
    }

    public void savePlayerDataImmediate(UUID uuid, PlayerInventory inventory) {
        ReentrantLock lock = playerLocks.computeIfAbsent(uuid, k -> new ReentrantLock());

        if (!lock.tryLock()) {
            logDebug("Could not acquire lock for immediate save of " + uuid);
            // Schedule retry
            saveExecutor.schedule(() -> savePlayerDataImmediate(uuid, inventory), 100, TimeUnit.MILLISECONDS);
            return;
        }

        try {
            // Cancel any pending saves
            ScheduledFuture<?> pendingSave = pendingSaves.remove(uuid);
            if (pendingSave != null) {
                pendingSave.cancel(false);
            }

            // Perform synchronous save for quit events
            performSave(uuid, inventory, true);

        } finally {
            lock.unlock();
        }
    }

    public void savePlayerData(UUID uuid, PlayerInventory inventory) {
        // Cancel existing pending save
        ScheduledFuture<?> existing = pendingSaves.get(uuid);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }

        // Schedule new save with debounce
        ScheduledFuture<?> future = saveExecutor.schedule(() -> {
            ReentrantLock lock = playerLocks.computeIfAbsent(uuid, k -> new ReentrantLock());

            if (lock.tryLock()) {
                try {
                    performSave(uuid, inventory, false);
                } finally {
                    lock.unlock();
                }
            } else {
                logDebug("Could not acquire lock for scheduled save of " + uuid);
            }
        }, 1, TimeUnit.SECONDS);

        pendingSaves.put(uuid, future);
    }

    private void performSave(UUID uuid, PlayerInventory inventory, boolean immediate) {
        // Skopiuj inventory bez slotu 17
        ItemStack[] contents = inventory.getContents().clone();
        if (contents.length > 17) {
            contents[17] = null; // Nie zapisuj menu item
        }
        
        String inventoryData = SerializationUtils.serializeItemStackArray(contents);
        String armorData = SerializationUtils.serializeItemStackArray(inventory.getArmorContents());
        String checksum = calculateChecksum(inventoryData + armorData);

        // Update cache
        int currentVersion = dataVersion.getOrDefault(uuid, 0);
        PlayerDataCache newCache = new PlayerDataCache(inventoryData, armorData, checksum, currentVersion + 1);
        dataCache.put(uuid, newCache);

        // Create backup first
        createBackup(uuid, inventoryData, armorData, immediate ? "QUIT" : "PERIODIC");

        // Save to database with retry logic
        CompletableFuture<Boolean> saveFuture = saveToDatabase(uuid, inventoryData, armorData, checksum, currentVersion);

        if (immediate) {
            // Wait for save to complete on quit
            try {
                boolean saved = saveFuture.get(3, TimeUnit.SECONDS);
                if (!saved) {
                    plugin.getLogger().severe("Failed to save data for " + uuid + " on quit!");
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error saving data on quit: " + e.getMessage());
            }
        }
    }

    private CompletableFuture<Boolean> saveToDatabase(UUID uuid, String inventoryData, String armorData, String checksum, int expectedVersion) {
        return CompletableFuture.supplyAsync(() -> {
            int attempts = 0;

            while (attempts < maxRetryAttempts) {
                attempts++;

                try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                    conn.setAutoCommit(false);

                    // Check version for optimistic locking
                    try (PreparedStatement checkStmt = conn.prepareStatement(
                            "SELECT version FROM player_data_info WHERE uuid = ?")) {
                        checkStmt.setString(1, uuid.toString());

                        try (ResultSet rs = checkStmt.executeQuery()) {
                            if (rs.next()) {
                                int dbVersion = rs.getInt("version");
                                if (dbVersion > expectedVersion) {
                                    plugin.getLogger().warning("Version conflict for " + uuid +
                                            " (expected: " + expectedVersion + ", found: " + dbVersion + ")");
                                    conn.rollback();
                                    return false;
                                }
                            }
                        }
                    }

                    // Perform update with version increment
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "INSERT INTO player_data_info (uuid, inventory, armor, checksum, version) " +
                                    "VALUES (?, ?, ?, ?, ?) " +
                                    "ON DUPLICATE KEY UPDATE " +
                                    "inventory = VALUES(inventory), " +
                                    "armor = VALUES(armor), " +
                                    "checksum = VALUES(checksum), " +
                                    "version = VALUES(version)")) {

                        stmt.setString(1, uuid.toString());
                        stmt.setString(2, inventoryData);
                        stmt.setString(3, armorData);
                        stmt.setString(4, checksum);
                        stmt.setInt(5, expectedVersion + 1);

                        stmt.executeUpdate();
                    }

                    // Log transaction
                    try (PreparedStatement logStmt = conn.prepareStatement(
                            "INSERT INTO transaction_log (uuid, action, status, details) VALUES (?, ?, ?, ?)")) {
                        logStmt.setString(1, uuid.toString());
                        logStmt.setString(2, "SAVE");
                        logStmt.setString(3, "SUCCESS");
                        logStmt.setString(4, "Version: " + (expectedVersion + 1) + ", Checksum: " + checksum.substring(0, 8));
                        logStmt.executeUpdate();
                    }

                    conn.commit();

                    // Update local version tracking
                    dataVersion.put(uuid, expectedVersion + 1);
                    lastKnownChecksum.put(uuid, checksum);

                    logDebug("Successfully saved data for " + uuid + " (version: " + (expectedVersion + 1) + ")");
                    return true;

                } catch (SQLException e) {
                    if (attempts >= maxRetryAttempts) {
                        plugin.getLogger().severe("Failed to save after " + attempts + " attempts: " + e.getMessage());
                        return false;
                    }

                    try {
                        Thread.sleep(retryDelayMs * attempts);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }

            return false;
        }, saveExecutor);
    }

    private void createBackup(UUID uuid, String inventoryData, String armorData, String reason) {
        saveExecutor.execute(() -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT INTO player_data_backup (uuid, inventory, armor, reason) VALUES (?, ?, ?, ?)")) {

                stmt.setString(1, uuid.toString());
                stmt.setString(2, inventoryData);
                stmt.setString(3, armorData);
                stmt.setString(4, reason);

                stmt.executeUpdate();

                // Clean old backups (keep last 10)
                try (PreparedStatement cleanStmt = conn.prepareStatement(
                        "DELETE FROM player_data_backup WHERE uuid = ? AND id NOT IN " +
                                "(SELECT id FROM (SELECT id FROM player_data_backup WHERE uuid = ? " +
                                "ORDER BY backup_time DESC LIMIT 10) AS t)")) {
                    cleanStmt.setString(1, uuid.toString());
                    cleanStmt.setString(2, uuid.toString());
                    cleanStmt.executeUpdate();
                }

            } catch (Exception e) {
                logDebug("Failed to create backup: " + e.getMessage());
            }
        });
    }

    public void shutdown() {
        // Save all online players
        for (Player player : Bukkit.getOnlinePlayers()) {
            savePlayerDataImmediate(player.getUniqueId(), player.getInventory());
        }

        // Shutdown executors
        saveExecutor.shutdown();
        loadExecutor.shutdown();

        try {
            if (!saveExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                saveExecutor.shutdownNow();
            }
            if (!loadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                loadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            saveExecutor.shutdownNow();
            loadExecutor.shutdownNow();
        }
    }

    // Event handlers for inventory changes - using debounced saves
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player) {
            Player player = (Player) event.getPlayer();
            savePlayerData(player.getUniqueId(), player.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        savePlayerData(event.getPlayer().getUniqueId(), event.getPlayer().getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player) {
            Player player = (Player) event.getWhoClicked();
            savePlayerData(player.getUniqueId(), player.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            savePlayerData(player.getUniqueId(), player.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        // Immediate save on death
        savePlayerDataImmediate(event.getEntity().getUniqueId(), event.getEntity().getInventory());
    }
}