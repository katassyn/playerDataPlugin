package com.maks.playerdataplugin;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PlayerStatsListener implements Listener {

    private final Main plugin;
    private final PlayerStatsManager statsManager;
    private boolean debugMode = false;
    // Set to track players who recently used the suicide command
    private final Set<UUID> recentSuicideCommandUsers = new HashSet<>();
    // Whether to count deaths caused by the suicide command
    private boolean countSuicideCommandDeaths = true;

    public PlayerStatsListener(Main plugin, PlayerStatsManager statsManager) {
        this.plugin = plugin;
        this.statsManager = statsManager;
        this.debugMode = plugin.getConfig().getBoolean("debug", false);
        this.countSuicideCommandDeaths = plugin.getConfig().getBoolean("deathStats.countSuicideCommandDeaths", true);

        if (!countSuicideCommandDeaths) {
            plugin.getLogger().info("Deaths caused by the /suicide command will not be counted in player statistics");
        }
    }

    private void logDebug(String message) {
        if (debugMode) {
            plugin.getLogger().info("[STATS-DEBUG] " + message);
        }
    }

    /**
     * Listens for player commands to detect when a player uses the /suicide command
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        // If we're configured to count suicide command deaths, no need to track them
        if (countSuicideCommandDeaths) {
            return;
        }

        String command = event.getMessage().toLowerCase().trim();

        // Check if the command is /suicide
        if (command.equals("/suicide") || command.startsWith("/suicide ")) {
            Player player = event.getPlayer();
            UUID playerUUID = player.getUniqueId();

            logDebug("Player " + player.getName() + " used the suicide command via chat/console");
            markPlayerForSuicideCommand(player);
        }
    }

    /**
     * Also listen for server commands in case suicide is executed differently
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerCommand(ServerCommandEvent event) {
        if (countSuicideCommandDeaths) {
            return;
        }

        String command = event.getCommand().toLowerCase().trim();

        // Check for execute commands that might run suicide for a player
        if (command.startsWith("execute") && command.contains("suicide")) {
            logDebug("Detected potential suicide command via execute: " + command);
            // This is more complex to parse, but we'll handle it in the death event
        }
    }

    /**
     * Helper method to mark a player as having used suicide command
     */
    private void markPlayerForSuicideCommand(Player player) {
        UUID playerUUID = player.getUniqueId();

        logDebug("Marking player " + player.getName() + " as suicide command user");

        // Add player to the tracking set
        recentSuicideCommandUsers.add(playerUUID);

        // Remove player from tracking set after a longer delay to ensure we catch the death
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            boolean removed = recentSuicideCommandUsers.remove(playerUUID);
            if (removed) {
                logDebug("Removed " + player.getName() + " from suicide command tracking (timeout)");
            }
        }, 200L); // 10 seconds (200 ticks) - longer timeout to be safe
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getName();

        logDebug("Player " + playerName + " joined, loading stats and starting playtime tracking");

        // Load player stats
        statsManager.loadPlayerStats(uuid);

        // Start playtime tracking
        statsManager.startPlaytimeTracking(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getName();

        logDebug("Player " + playerName + " quit, stopping playtime tracking and saving stats");

        // Stop playtime tracking
        statsManager.stopPlaytimeTracking(uuid);

        // Save player stats
        statsManager.savePlayerStats(uuid);

        // Remove from cache to free memory
        statsManager.removeFromCache(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        // Check if the killer is a player
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        Entity victim = event.getEntity();

        // If victim is a player, handle it in PlayerDeathEvent
        if (victim instanceof Player) return;

        // This is a mob kill
        UUID killerUUID = killer.getUniqueId();
        String mobType = victim.getType().name();

        logDebug("Player " + killer.getName() + " killed mob: " + mobType);

        // Increment mob kills
        statsManager.incrementMobKills(killerUUID);

        // Save stats periodically (every 10 mob kills to reduce database load)
        PlayerStatsManager.PlayerStats stats = statsManager.getPlayerStats(killerUUID);
        if (stats != null && stats.getMobsKilled() % 10 == 0) {
            statsManager.savePlayerStats(killerUUID);
            logDebug("Auto-saved stats for " + killer.getName() + " after " + stats.getMobsKilled() + " mob kills");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        UUID victimUUID = victim.getUniqueId();
        String victimName = victim.getName();

        logDebug("Player " + victimName + " died. Cause: " + (event.getDeathMessage() != null ? event.getDeathMessage() : "Unknown"));

        // Check if this death was caused by the suicide command
        boolean isSuicideCommand = recentSuicideCommandUsers.remove(victimUUID);

        // Additional check: if death message contains suicide-related text
        if (!isSuicideCommand && !countSuicideCommandDeaths) {
            String deathMessage = event.getDeathMessage();
            if (deathMessage != null) {
                String lowerDeathMessage = deathMessage.toLowerCase();
                // Check for common suicide-related death messages
                if (lowerDeathMessage.contains("died") && killer == null) {
                    // If player died without a killer and we just detected potential suicide command usage
                    // This is a fallback detection method
                    logDebug("Potential suicide death detected via death message analysis for " + victimName);

                    // Additional check: if player's health was set to 0 recently (indicating manual suicide)
                    if (victim.getHealth() <= 0) {
                        isSuicideCommand = true;
                        logDebug("Confirmed suicide death for " + victimName + " via health check");
                    }
                }
            }
        }

        if (isSuicideCommand && !countSuicideCommandDeaths) {
            logDebug("Death of " + victimName + " was caused by suicide command - not counting as a death");
        } else {
            // Increment death count if it wasn't a suicide command or if we're configured to count suicide deaths
            statsManager.incrementDeaths(victimUUID);
            if (isSuicideCommand) {
                logDebug("Death of " + victimName + " was caused by suicide command but counting as a death (per config)");
            } else {
                logDebug("Death of " + victimName + " counted as normal death");
            }
        }

        // If killer is a player, increment their player kill count
        if (killer != null && killer instanceof Player) {
            UUID killerUUID = killer.getUniqueId();
            String killerName = killer.getName();

            logDebug("Player " + killerName + " killed player " + victimName);
            statsManager.incrementPlayerKills(killerUUID);

            // Save killer's stats
            statsManager.savePlayerStats(killerUUID);
        }

        // Save victim's stats
        statsManager.savePlayerStats(victimUUID);
    }

    // Periodic stats save for online players (called from scheduled task in Main)
    public void saveAllOnlinePlayersStats() {
        logDebug("Saving stats for all online players");

        plugin.getServer().getOnlinePlayers().forEach(player -> {
            UUID uuid = player.getUniqueId();

            // Update balance from Vault before saving
            if (statsManager.getEconomy() != null) {
                double currentBalance = statsManager.getEconomy().getBalance(player);
                PlayerStatsManager.PlayerStats stats = statsManager.getPlayerStats(uuid);
                if (stats != null) {
                    stats.setBalance(currentBalance);
                }
            }

            statsManager.savePlayerStats(uuid);
        });
    }

    /**
     * Public method to manually mark a player as using suicide command
     * This can be called by other plugins if needed
     */
    public void markSuicideCommandUsage(Player player) {
        if (!countSuicideCommandDeaths) {
            markPlayerForSuicideCommand(player);
        }
    }
}
