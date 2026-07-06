package com.maks.playerdataplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors data integrity and provides recovery mechanisms
 */
public class DataIntegrityMonitor {

    private final Main plugin;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ConcurrentHashMap<UUID, IntegrityStatus> playerStatus = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastVerification = new ConcurrentHashMap<>();
    private final AtomicInteger corruptionDetected = new AtomicInteger(0);
    private final AtomicLong totalVerifications = new AtomicLong(0);

    // Configuration
    private final boolean enabled;
    private final long verificationInterval;
    private final boolean autoRecover;
    private final int maxRecoveryAttempts;

    public static class IntegrityStatus {
        public enum Status {
            HEALTHY, CORRUPTED, RECOVERING, FAILED
        }

        private final UUID playerUUID;
        private Status status;
        private String lastError;
        private long lastCheck;
        private int recoveryAttempts;

        public IntegrityStatus(UUID playerUUID) {
            this.playerUUID = playerUUID;
            this.status = Status.HEALTHY;
            this.lastCheck = System.currentTimeMillis();
            this.recoveryAttempts = 0;
        }

        // Getters and setters
        public Status getStatus() { return status; }
        public void setStatus(Status status) { this.status = status; }
        public String getLastError() { return lastError; }
        public void setLastError(String error) { this.lastError = error; }
        public long getLastCheck() { return lastCheck; }
        public void updateLastCheck() { this.lastCheck = System.currentTimeMillis(); }
        public int getRecoveryAttempts() { return recoveryAttempts; }
        public void incrementRecoveryAttempts() { this.recoveryAttempts++; }
        public void resetRecoveryAttempts() { this.recoveryAttempts = 0; }
    }

    public DataIntegrityMonitor(Main plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("safety.enableChecksums", true);
        this.verificationInterval = plugin.getConfig().getLong("safety.verificationIntervalMinutes", 30) * 60 * 1000;
        this.autoRecover = plugin.getConfig().getBoolean("safety.enableRecoveryMode", true);
        this.maxRecoveryAttempts = plugin.getConfig().getInt("safety.maxRecoveryAttempts", 3);

        if (enabled) {
            startMonitoring();
        }
    }

    private void startMonitoring() {
        // Schedule periodic integrity checks
        scheduler.scheduleWithFixedDelay(this::performIntegrityCheck,
                5, // Initial delay
                verificationInterval / 60000, // Convert to minutes
                TimeUnit.MINUTES
        );

        // Schedule cleanup of old verification data
        scheduler.scheduleWithFixedDelay(this::cleanupOldData,
                1, 24, TimeUnit.HOURS
        );

        plugin.getLogger().info("Data Integrity Monitor started with " +
                (verificationInterval / 60000) + " minute verification interval");
    }

    /**
     * Performs integrity check for all online players
     */
    private void performIntegrityCheck() {
        if (plugin.isShuttingDown()) return;

        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        if (onlinePlayers.isEmpty()) return;

        plugin.getLogger().info("Starting integrity check for " + onlinePlayers.size() + " players");

        for (Player player : onlinePlayers) {
            verifyPlayerData(player.getUniqueId()).thenAccept(isValid -> {
                if (!isValid && autoRecover) {
                    attemptRecovery(player.getUniqueId());
                }
            });
        }
    }

    /**
     * Verifies data integrity for a specific player
     */
    public CompletableFuture<Boolean> verifyPlayerData(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            totalVerifications.incrementAndGet();
            lastVerification.put(playerUUID, System.currentTimeMillis());

            IntegrityStatus status = playerStatus.computeIfAbsent(playerUUID, IntegrityStatus::new);
            status.updateLastCheck();

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT inventory, armor, checksum FROM player_data_info WHERE uuid = ?")) {

                stmt.setString(1, playerUUID.toString());

                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        // No data found - this is okay for new players
                        status.setStatus(IntegrityStatus.Status.HEALTHY);
                        return true;
                    }

                    String inventoryData = rs.getString("inventory");
                    String armorData = rs.getString("armor");
                    String storedChecksum = rs.getString("checksum");

                    // Calculate current checksum
                    String calculatedChecksum = calculateChecksum(inventoryData + armorData);

                    if (storedChecksum == null || storedChecksum.isEmpty()) {
                        // No checksum stored - update it
                        updateChecksum(playerUUID, calculatedChecksum);
                        status.setStatus(IntegrityStatus.Status.HEALTHY);
                        return true;
                    }

                    if (!storedChecksum.equals(calculatedChecksum)) {
                        // Checksum mismatch - data corruption detected
                        corruptionDetected.incrementAndGet();
                        status.setStatus(IntegrityStatus.Status.CORRUPTED);
                        status.setLastError("Checksum mismatch: expected " + storedChecksum +
                                ", got " + calculatedChecksum);

                        plugin.getLogger().severe("Data corruption detected for player " + playerUUID);
                        logCorruption(playerUUID, "CHECKSUM_MISMATCH", status.getLastError());

                        // Notify online admins
                        notifyAdmins("§c[DataIntegrity] Corruption detected for player " +
                                Bukkit.getOfflinePlayer(playerUUID).getName());

                        return false;
                    }

                    // Additional validation checks
                    if (!validateInventoryData(inventoryData, armorData)) {
                        status.setStatus(IntegrityStatus.Status.CORRUPTED);
                        status.setLastError("Invalid inventory data structure");

                        plugin.getLogger().warning("Invalid data structure for player " + playerUUID);
                        logCorruption(playerUUID, "INVALID_STRUCTURE", status.getLastError());

                        return false;
                    }

                    status.setStatus(IntegrityStatus.Status.HEALTHY);
                    status.resetRecoveryAttempts();
                    return true;
                }

            } catch (Exception e) {
                status.setStatus(IntegrityStatus.Status.FAILED);
                status.setLastError("Verification error: " + e.getMessage());
                plugin.getLogger().severe("Failed to verify data for " + playerUUID + ": " + e.getMessage());
                return false;
            }
        });
    }

    /**
     * Validates inventory data structure
     */
    private boolean validateInventoryData(String inventoryData, String armorData) {
        if (inventoryData == null || inventoryData.isEmpty()) {
            return true; // Empty inventory is valid
        }

        try {
            // Try to deserialize to check validity
            ItemStack[] items = SerializationUtils.deserializeItemStackArray(inventoryData);
            if (items == null) return false;

            // Check for impossible values
            for (ItemStack item : items) {
                if (item != null) {
                    if (item.getAmount() < 0 || item.getAmount() > 127) {
                        return false;
                    }
                }
            }

            if (armorData != null && !armorData.isEmpty()) {
                ItemStack[] armor = SerializationUtils.deserializeItemStackArray(armorData);
                if (armor == null || armor.length != 4) { // Armor should always be 4 slots
                    return false;
                }
            }

            return true;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Attempts to recover corrupted player data
     */
    public CompletableFuture<Boolean> attemptRecovery(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            IntegrityStatus status = playerStatus.get(playerUUID);
            if (status == null) {
                status = new IntegrityStatus(playerUUID);
                playerStatus.put(playerUUID, status);
            }

            if (status.getRecoveryAttempts() >= maxRecoveryAttempts) {
                plugin.getLogger().severe("Max recovery attempts reached for " + playerUUID);
                status.setStatus(IntegrityStatus.Status.FAILED);
                return false;
            }

            status.incrementRecoveryAttempts();
            status.setStatus(IntegrityStatus.Status.RECOVERING);

            plugin.getLogger().info("Attempting recovery for " + playerUUID +
                    " (attempt " + status.getRecoveryAttempts() + "/" + maxRecoveryAttempts + ")");

            try {
                // Try to recover from backup
                if (recoverFromBackup(playerUUID)) {
                    status.setStatus(IntegrityStatus.Status.HEALTHY);
                    status.resetRecoveryAttempts();

                    plugin.getLogger().info("Successfully recovered data for " + playerUUID + " from backup");
                    notifyAdmins("§a[DataIntegrity] Successfully recovered data for " +
                            Bukkit.getOfflinePlayer(playerUUID).getName());

                    return true;
                }

                // Try to recover from transaction log
                if (recoverFromTransactionLog(playerUUID)) {
                    status.setStatus(IntegrityStatus.Status.HEALTHY);
                    status.resetRecoveryAttempts();

                    plugin.getLogger().info("Successfully recovered data for " + playerUUID + " from transaction log");
                    return true;
                }

                // If player is online, save current inventory as recovery
                Player player = Bukkit.getPlayer(playerUUID);
                if (player != null && player.isOnline()) {
                    if (saveCurrentInventoryAsRecovery(player)) {
                        status.setStatus(IntegrityStatus.Status.HEALTHY);
                        status.resetRecoveryAttempts();

                        plugin.getLogger().info("Saved current inventory as recovery for " + playerUUID);
                        return true;
                    }
                }

                status.setStatus(IntegrityStatus.Status.FAILED);
                plugin.getLogger().severe("All recovery attempts failed for " + playerUUID);

                return false;

            } catch (Exception e) {
                status.setStatus(IntegrityStatus.Status.FAILED);
                status.setLastError("Recovery failed: " + e.getMessage());
                plugin.getLogger().severe("Recovery failed for " + playerUUID + ": " + e.getMessage());
                return false;
            }
        });
    }

    /**
     * Recovers data from the most recent valid backup
     */
    private boolean recoverFromBackup(UUID playerUUID) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            // Find most recent valid backup
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT inventory, armor, backup_time FROM player_data_backup " +
                            "WHERE uuid = ? ORDER BY backup_time DESC LIMIT 10")) {

                stmt.setString(1, playerUUID.toString());

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String inventoryData = rs.getString("inventory");
                        String armorData = rs.getString("armor");

                        // Validate backup data
                        if (validateInventoryData(inventoryData, armorData)) {
                            // Restore from this backup
                            String checksum = calculateChecksum(inventoryData + armorData);

                            try (PreparedStatement restoreStmt = conn.prepareStatement(
                                    "REPLACE INTO player_data_info (uuid, inventory, armor, checksum, version) " +
                                            "VALUES (?, ?, ?, ?, 0)")) {

                                restoreStmt.setString(1, playerUUID.toString());
                                restoreStmt.setString(2, inventoryData);
                                restoreStmt.setString(3, armorData);
                                restoreStmt.setString(4, checksum);

                                restoreStmt.executeUpdate();

                                logRecovery(playerUUID, "BACKUP", rs.getTimestamp("backup_time").toString());
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to recover from backup: " + e.getMessage());
        }

        return false;
    }

    /**
     * Recovers data from transaction log
     */
    private boolean recoverFromTransactionLog(UUID playerUUID) {
        // This would implement recovery from transaction log
        // For now, returning false as placeholder
        return false;
    }

    /**
     * Saves current player inventory as recovery
     */
    private boolean saveCurrentInventoryAsRecovery(Player player) {
        try {
            String inventoryData = SerializationUtils.serializeItemStackArray(
                    player.getInventory().getContents()
            );
            String armorData = SerializationUtils.serializeItemStackArray(
                    player.getInventory().getArmorContents()
            );
            String checksum = calculateChecksum(inventoryData + armorData);

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "REPLACE INTO player_data_info (uuid, inventory, armor, checksum, version) " +
                                 "VALUES (?, ?, ?, ?, 0)")) {

                stmt.setString(1, player.getUniqueId().toString());
                stmt.setString(2, inventoryData);
                stmt.setString(3, armorData);
                stmt.setString(4, checksum);

                stmt.executeUpdate();

                logRecovery(player.getUniqueId(), "CURRENT_INVENTORY", "Player online");
                return true;
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save current inventory as recovery: " + e.getMessage());
        }

        return false;
    }

    /**
     * Updates checksum in database
     */
    private void updateChecksum(UUID playerUUID, String checksum) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE player_data_info SET checksum = ? WHERE uuid = ?")) {

            stmt.setString(1, checksum);
            stmt.setString(2, playerUUID.toString());
            stmt.executeUpdate();

        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to update checksum: " + e.getMessage());
        }
    }

    /**
     * Logs corruption detection
     */
    private void logCorruption(UUID playerUUID, String type, String details) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO transaction_log (uuid, action, status, details) VALUES (?, ?, ?, ?)")) {

            stmt.setString(1, playerUUID.toString());
            stmt.setString(2, "CORRUPTION_" + type);
            stmt.setString(3, "ERROR");
            stmt.setString(4, details);
            stmt.executeUpdate();

        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to log corruption: " + e.getMessage());
        }
    }

    /**
     * Logs successful recovery
     */
    private void logRecovery(UUID playerUUID, String method, String details) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO transaction_log (uuid, action, status, details) VALUES (?, ?, ?, ?)")) {

            stmt.setString(1, playerUUID.toString());
            stmt.setString(2, "RECOVERY_" + method);
            stmt.setString(3, "SUCCESS");
            stmt.setString(4, details);
            stmt.executeUpdate();

        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to log recovery: " + e.getMessage());
        }
    }

    /**
     * Notifies online admins about integrity issues
     */
    private void notifyAdmins(String message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("playerdataplugin.admin")) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            }
        }
    }

    /**
     * Cleans up old verification data
     */
    private void cleanupOldData() {
        long cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000); // 24 hours

        lastVerification.entrySet().removeIf(entry -> entry.getValue() < cutoff);

        // Clean old transaction logs
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM transaction_log WHERE timestamp < DATE_SUB(NOW(), INTERVAL ? DAY)")) {

            int retentionDays = plugin.getConfig().getInt("safety.transactionLogRetentionDays", 7);
            stmt.setInt(1, retentionDays);
            int deleted = stmt.executeUpdate();

            if (deleted > 0) {
                plugin.getLogger().info("Cleaned up " + deleted + " old transaction log entries");
            }

        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to clean up old logs: " + e.getMessage());
        }
    }

    /**
     * Calculates SHA-256 checksum
     */
    private String calculateChecksum(String data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    /**
     * Gets integrity statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalVerifications", totalVerifications.get());
        stats.put("corruptionDetected", corruptionDetected.get());
        stats.put("playersMonitored", playerStatus.size());

        int healthy = 0, corrupted = 0, recovering = 0, failed = 0;
        for (IntegrityStatus status : playerStatus.values()) {
            switch (status.getStatus()) {
                case HEALTHY: healthy++; break;
                case CORRUPTED: corrupted++; break;
                case RECOVERING: recovering++; break;
                case FAILED: failed++; break;
            }
        }

        stats.put("healthyPlayers", healthy);
        stats.put("corruptedPlayers", corrupted);
        stats.put("recoveringPlayers", recovering);
        stats.put("failedPlayers", failed);

        return stats;
    }

    /**
     * Shuts down the monitor
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }
}