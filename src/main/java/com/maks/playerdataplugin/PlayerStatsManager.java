package com.maks.playerdataplugin;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class PlayerStatsManager {

    private final Main plugin;
    private Economy economy = null;
    private final Map<UUID, PlayerStats> statsCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sessionStartTimes = new ConcurrentHashMap<>();
    private boolean debugMode = false;

    public static class PlayerStats {
        private int mobsKilled;
        private int playersKilled;
        private int deaths;
        private double playtimeHours;
        private double balance;

        public PlayerStats(int mobsKilled, int playersKilled, int deaths, double playtimeHours, double balance) {
            this.mobsKilled = mobsKilled;
            this.playersKilled = playersKilled;
            this.deaths = deaths;
            this.playtimeHours = playtimeHours;
            this.balance = balance;
        }

        // Getters
        public int getMobsKilled() { return mobsKilled; }
        public int getPlayersKilled() { return playersKilled; }
        public int getDeaths() { return deaths; }
        public double getPlaytimeHours() { return playtimeHours; }
        public double getBalance() { return balance; }

        // Setters
        public void setMobsKilled(int mobsKilled) { this.mobsKilled = mobsKilled; }
        public void setPlayersKilled(int playersKilled) { this.playersKilled = playersKilled; }
        public void setDeaths(int deaths) { this.deaths = deaths; }
        public void setPlaytimeHours(double playtimeHours) { this.playtimeHours = playtimeHours; }
        public void setBalance(double balance) { this.balance = balance; }

        // Increment methods
        public void incrementMobsKilled() { this.mobsKilled++; }
        public void incrementPlayersKilled() { this.playersKilled++; }
        public void incrementDeaths() { this.deaths++; }
        public void addPlaytime(double hours) { this.playtimeHours += hours; }
    }

    public PlayerStatsManager(Main plugin) {
        this.plugin = plugin;
        this.debugMode = plugin.getConfig().getBoolean("debug", false);
        setupEconomy();
    }

    private void logDebug(String message) {
        if (debugMode) {
            plugin.getLogger().info("[STATS-DEBUG] " + message);
        }
    }

    private boolean setupEconomy() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            logDebug("Vault plugin not found, economy features disabled");
            return false;
        }

        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            logDebug("Economy service provider not found");
            return false;
        }

        economy = rsp.getProvider();
        logDebug("Economy service connected: " + (economy != null));
        return economy != null;
    }

    public void createStatsTable() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS player_stats (" +
                "uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                "username VARCHAR(16)," +
                "mobs_killed INT DEFAULT 0," +
                "players_killed INT DEFAULT 0," +
                "deaths INT DEFAULT 0," +
                "playtime_hours DOUBLE DEFAULT 0.0," +
                "balance DOUBLE DEFAULT 0.0," +
                "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                ");";

        try (Connection connection = plugin.getDatabaseManager().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
            logDebug("Player stats table created/verified");
        }
    }

    public void loadPlayerStats(UUID uuid) {
        logDebug("Loading stats for player " + uuid);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT username, mobs_killed, players_killed, deaths, playtime_hours, balance FROM player_stats WHERE uuid=?")) {

                stmt.setString(1, uuid.toString());

                try (ResultSet rs = stmt.executeQuery()) {
                    PlayerStats stats;
                    if (rs.next()) {
                        String storedUsername = rs.getString("username");
                        stats = new PlayerStats(
                                rs.getInt("mobs_killed"),
                                rs.getInt("players_killed"),
                                rs.getInt("deaths"),
                                rs.getDouble("playtime_hours"),
                                rs.getDouble("balance")
                        );
                        logDebug("Loaded existing stats for " + uuid + " (username: " + storedUsername + "): " + 
                                "mobs=" + stats.getMobsKilled() + 
                                ", players=" + stats.getPlayersKilled() + 
                                ", deaths=" + stats.getDeaths() + 
                                ", playtime=" + String.format("%.2f", stats.getPlaytimeHours()) + "h");
                    } else {
                        // Create new stats entry
                        stats = new PlayerStats(0, 0, 0, 0.0, 0.0);
                        logDebug("Created new stats entry for " + uuid);
                    }

                    statsCache.put(uuid, stats);

                    // Update balance from Vault if available
                    if (economy != null) {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player != null && player.isOnline()) {
                            double currentBalance = economy.getBalance(player);
                            stats.setBalance(currentBalance);
                            logDebug("Updated balance from Vault for " + uuid + ": " + currentBalance);
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to load player stats for " + uuid);
                e.printStackTrace();
            }
        });
    }

    public void savePlayerStats(UUID uuid) {
        PlayerStats stats = statsCache.get(uuid);
        if (stats == null) {
            logDebug("No stats to save for player " + uuid);
            return;
        }

        logDebug("Saving stats for player " + uuid);

        // Get current username
        String username = null;
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            username = player.getName();

            // Update balance from Vault before saving
            if (economy != null && player.isOnline()) {
                double currentBalance = economy.getBalance(player);
                stats.setBalance(currentBalance);
                logDebug("Updated balance before save for " + uuid + ": " + currentBalance);
            }
        }

        final String finalUsername = username;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "REPLACE INTO player_stats (uuid, username, mobs_killed, players_killed, deaths, playtime_hours, balance) VALUES (?, ?, ?, ?, ?, ?, ?)")) {

                stmt.setString(1, uuid.toString());
                stmt.setString(2, finalUsername);
                stmt.setInt(3, stats.getMobsKilled());
                stmt.setInt(4, stats.getPlayersKilled());
                stmt.setInt(5, stats.getDeaths());
                stmt.setDouble(6, stats.getPlaytimeHours());
                stmt.setDouble(7, stats.getBalance());

                stmt.executeUpdate();
                logDebug("Successfully saved stats for " + uuid + " (username: " + finalUsername + ")" +
                        ": mobs=" + stats.getMobsKilled() + 
                        ", players=" + stats.getPlayersKilled() + 
                        ", deaths=" + stats.getDeaths() + 
                        ", playtime=" + String.format("%.2f", stats.getPlaytimeHours()) + "h" +
                        ", balance=" + String.format("%.2f", stats.getBalance()));

            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to save player stats for " + uuid);
                e.printStackTrace();
            }
        });
    }

    public void startPlaytimeTracking(UUID uuid) {
        sessionStartTimes.put(uuid, System.currentTimeMillis());
        logDebug("Started playtime tracking for " + uuid);
    }

    public void stopPlaytimeTracking(UUID uuid) {
        Long startTime = sessionStartTimes.remove(uuid);
        if (startTime != null) {
            long sessionTime = System.currentTimeMillis() - startTime;
            double sessionHours = sessionTime / (1000.0 * 60.0 * 60.0);

            PlayerStats stats = statsCache.get(uuid);
            if (stats != null) {
                stats.addPlaytime(sessionHours);
                logDebug("Stopped playtime tracking for " + uuid + 
                        ", session duration: " + String.format("%.3f", sessionHours) + " hours" +
                        ", total playtime: " + String.format("%.2f", stats.getPlaytimeHours()) + " hours");
            }
        }
    }

    // Public methods for incrementing stats
    public void incrementMobKills(UUID uuid) {
        PlayerStats stats = statsCache.get(uuid);
        if (stats != null) {
            stats.incrementMobsKilled();
            logDebug("Incremented mob kills for " + uuid + " to " + stats.getMobsKilled());
        }
    }

    public void incrementPlayerKills(UUID uuid) {
        PlayerStats stats = statsCache.get(uuid);
        if (stats != null) {
            stats.incrementPlayersKilled();
            logDebug("Incremented player kills for " + uuid + " to " + stats.getPlayersKilled());
        }
    }

    public void incrementDeaths(UUID uuid) {
        PlayerStats stats = statsCache.get(uuid);
        if (stats != null) {
            stats.incrementDeaths();
            logDebug("Incremented deaths for " + uuid + " to " + stats.getDeaths());
        }
    }

    public PlayerStats getPlayerStats(UUID uuid) {
        return statsCache.get(uuid);
    }

    public void removeFromCache(UUID uuid) {
        statsCache.remove(uuid);
        sessionStartTimes.remove(uuid);
        logDebug("Removed " + uuid + " from stats cache");
    }

    public Economy getEconomy() {
        return economy;
    }

    // Method to find UUID by username from database
    public void findPlayerByUsername(String username, java.util.function.Consumer<UUID> callback) {
        logDebug("Searching for player by username: " + username);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT uuid FROM player_stats WHERE LOWER(username) = LOWER(?) LIMIT 1")) {

                stmt.setString(1, username);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String uuidString = rs.getString("uuid");
                        UUID uuid = UUID.fromString(uuidString);
                        logDebug("Found UUID " + uuid + " for username " + username);

                        // Run callback on main thread
                        Bukkit.getScheduler().runTask(plugin, () -> callback.accept(uuid));
                    } else {
                        logDebug("No UUID found for username " + username);
                        // Run callback on main thread with null
                        Bukkit.getScheduler().runTask(plugin, () -> callback.accept(null));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to search for player by username: " + username);
                e.printStackTrace();
                // Run callback on main thread with null
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(null));
            }
        });
    }

    // Method to get stats by UUID, loading from database if not cached
    public void getPlayerStatsByUUID(UUID uuid, java.util.function.Consumer<PlayerStats> callback) {
        PlayerStats cachedStats = statsCache.get(uuid);
        if (cachedStats != null) {
            // Return cached stats immediately
            callback.accept(cachedStats);
            return;
        }

        logDebug("Stats not cached for " + uuid + ", loading from database");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT username, mobs_killed, players_killed, deaths, playtime_hours, balance FROM player_stats WHERE uuid=?")) {

                stmt.setString(1, uuid.toString());

                try (ResultSet rs = stmt.executeQuery()) {
                    PlayerStats stats;
                    if (rs.next()) {
                        String storedUsername = rs.getString("username");
                        stats = new PlayerStats(
                                rs.getInt("mobs_killed"),
                                rs.getInt("players_killed"),
                                rs.getInt("deaths"),
                                rs.getDouble("playtime_hours"),
                                rs.getDouble("balance")
                        );
                        logDebug("Loaded stats from database for " + uuid + " (username: " + storedUsername + ")");
                    } else {
                        // No stats found
                        stats = null;
                        logDebug("No stats found in database for " + uuid);
                    }

                    // Run callback on main thread
                    Bukkit.getScheduler().runTask(plugin, () -> callback.accept(stats));
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to load player stats for " + uuid);
                e.printStackTrace();
                // Run callback on main thread with null
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(null));
            }
        });
    }
}
