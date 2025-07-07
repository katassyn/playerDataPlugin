package com.maks.playerdataplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class StatsCommand implements CommandExecutor, TabCompleter {

    private final Main plugin;
    private final PlayerStatsManager statsManager;

    public StatsCommand(Main plugin, PlayerStatsManager statsManager) {
        this.plugin = plugin;
        this.statsManager = statsManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // Show own stats if player
            if (sender instanceof Player) {
                showStats(sender, (Player) sender);
            } else {
                sender.sendMessage(ChatColor.RED + "Console must specify a player name: /stats <player>");
            }
            return true;
        }

        if (args.length == 1) {
            // Show specific player's stats
            String targetName = args[0];
            Player onlineTarget = Bukkit.getPlayer(targetName);

            if (onlineTarget != null) {
                // Player is online - use normal flow
                showPlayerStats(sender, onlineTarget, onlineTarget.getName());
            } else {
                // Player is offline - search by username in database
                if (!sender.hasPermission("playerdataplugin.stats.others")) {
                    sender.sendMessage(ChatColor.RED + "You don't have permission to view other players' stats.");
                    return true;
                }

                sender.sendMessage(ChatColor.YELLOW + "Searching for player '" + targetName + "'...");

                statsManager.findPlayerByUsername(targetName, uuid -> {
                    if (uuid == null) {
                        sender.sendMessage(ChatColor.RED + "Player '" + targetName + "' not found in database.");
                        return;
                    }

                    // Load stats for the found UUID
                    statsManager.getPlayerStatsByUUID(uuid, stats -> {
                        if (stats == null) {
                            sender.sendMessage(ChatColor.RED + "No stats found for player '" + targetName + "'.");
                            return;
                        }

                        showOfflinePlayerStats(sender, targetName, stats);
                    });
                });
            }
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            // Reload stats for a player (admin command)
            if (!sender.hasPermission("playerdataplugin.stats.reload")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to reload player stats.");
                return true;
            }

            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player '" + args[1] + "' not found or not online.");
                return true;
            }

            UUID uuid = target.getUniqueId();
            statsManager.savePlayerStats(uuid);
            statsManager.loadPlayerStats(uuid);

            sender.sendMessage(ChatColor.GREEN + "Reloaded stats for player " + target.getName());
            return true;
        }

        // Invalid usage
        sender.sendMessage(ChatColor.RED + "Usage:");
        sender.sendMessage(ChatColor.RED + "/stats - Show your stats");
        sender.sendMessage(ChatColor.RED + "/stats <player> - Show player's stats");
        if (sender.hasPermission("playerdataplugin.stats.reload")) {
            sender.sendMessage(ChatColor.RED + "/stats reload <player> - Reload player's stats");
        }
        return true;
    }

    private void showStats(CommandSender sender, Player target) {
        showPlayerStats(sender, target, target.getName());
    }

    private void showPlayerStats(CommandSender sender, Player target, String displayName) {
        UUID uuid = target.getUniqueId();
        PlayerStatsManager.PlayerStats stats = statsManager.getPlayerStats(uuid);

        if (stats == null) {
            sender.sendMessage(ChatColor.RED + "No stats available for " + displayName + ". Please wait for data to load.");
            return;
        }

        // Update balance from Vault if available and player is online
        if (statsManager.getEconomy() != null && target.isOnline()) {
            double currentBalance = statsManager.getEconomy().getBalance(target);
            stats.setBalance(currentBalance);
        }

        // Show stats
        showFormattedStats(sender, target, displayName, stats, true);
    }

    private void showOfflinePlayerStats(CommandSender sender, String playerName, PlayerStatsManager.PlayerStats stats) {
        // Show stats for offline player
        showFormattedStats(sender, null, playerName, stats, false);
    }

    private void showFormattedStats(CommandSender sender, Player target, String displayName, PlayerStatsManager.PlayerStats stats, boolean isOnline) {
        // Create formatted stats display
        String title;
        if (target != null && sender.equals(target)) {
            title = "Your Stats";
        } else {
            title = displayName + "'s Stats" + (isOnline ? "" : " (Offline)");
        }

        sender.sendMessage(ChatColor.GOLD + "═══════════════════════════════");
        sender.sendMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + title);
        sender.sendMessage(ChatColor.GOLD + "═══════════════════════════════");

        sender.sendMessage(ChatColor.GREEN + "🗡 Mobs Killed: " + ChatColor.WHITE + stats.getMobsKilled());
        sender.sendMessage(ChatColor.RED + "⚔ Players Killed: " + ChatColor.WHITE + stats.getPlayersKilled());
        sender.sendMessage(ChatColor.DARK_RED + "💀 Deaths: " + ChatColor.WHITE + stats.getDeaths());

        // Calculate K/D ratio
        double kdRatio = stats.getDeaths() > 0 ? (double) stats.getPlayersKilled() / stats.getDeaths() : stats.getPlayersKilled();
        sender.sendMessage(ChatColor.YELLOW + "📊 K/D Ratio: " + ChatColor.WHITE + String.format("%.2f", kdRatio));

        sender.sendMessage(ChatColor.BLUE + "⏰ Playtime: " + ChatColor.WHITE + formatPlaytime(stats.getPlaytimeHours()));

        if (statsManager.getEconomy() != null) {
            String balanceText = isOnline && target != null ? 
                statsManager.getEconomy().format(statsManager.getEconomy().getBalance(target)) :
                statsManager.getEconomy().format(stats.getBalance()) + " (last known)";
            sender.sendMessage(ChatColor.GOLD + "💰 Balance: " + ChatColor.WHITE + balanceText);
        }

        sender.sendMessage(ChatColor.GOLD + "═══════════════════════════════");
    }

    private String formatPlaytime(double hours) {
        if (hours < 1.0) {
            int minutes = (int) (hours * 60);
            return minutes + " minutes";
        } else if (hours < 24.0) {
            return String.format("%.1f hours", hours);
        } else {
            int days = (int) (hours / 24);
            double remainingHours = hours % 24;
            if (remainingHours < 0.1) {
                return days + " days";
            } else {
                return String.format("%d days, %.1f hours", days, remainingHours);
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // First argument: player names or "reload"
            String partial = args[0].toLowerCase();

            // Add "reload" if player has permission
            if (sender.hasPermission("playerdataplugin.stats.reload") && "reload".startsWith(partial)) {
                completions.add("reload");
            }

            // Add online player names
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(partial)) {
                    completions.add(player.getName());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            // Second argument for reload: player names
            String partial = args[1].toLowerCase();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(partial)) {
                    completions.add(player.getName());
                }
            }
        }

        return completions;
    }
}
