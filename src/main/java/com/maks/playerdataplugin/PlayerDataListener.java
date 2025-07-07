package com.maks.playerdataplugin;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.Map;

public class PlayerDataListener implements Listener {

    private final Main plugin;
    private final Set<UUID> savingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PlayerData> playerDataCache = new HashMap<>();
    private boolean debugMode = false;
    private int maxRetryAttempts = 3;
    private long retryDelayMs = 1000;

    // Inner class to store player data for caching
    private static class PlayerData {
        final String inventoryData;
        final String armorData;

        PlayerData(String inventoryData, String armorData) {
            this.inventoryData = inventoryData;
            this.armorData = armorData;
        }
    }

    public PlayerDataListener(Main plugin) {
        this.plugin = plugin;
        this.debugMode = plugin.getConfig().getBoolean("debug", false);
        this.maxRetryAttempts = plugin.getConfig().getInt("database.maxRetryAttempts", 3);
        this.retryDelayMs = plugin.getConfig().getLong("database.retryDelayMs", 1000);

        logDebug("PlayerDataListener initialized with maxRetryAttempts=" + maxRetryAttempts + ", retryDelayMs=" + retryDelayMs);
    }

    private void logDebug(String message) {
        if (debugMode) {
            plugin.getLogger().info("[DEBUG] " + message);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        PlayerInventory inventory = event.getPlayer().getInventory();
        String playerName = event.getPlayer().getName();

        logDebug("Player " + playerName + " (" + uuid + ") joined, loading inventory data");

        // Clear the inventory to prevent default items
        inventory.clear();
        inventory.setArmorContents(null);
        logDebug("Cleared inventory for player " + playerName);

        // Wait for any ongoing saves to complete
        if (savingPlayers.contains(uuid)) {
            logDebug("Player " + playerName + " has an ongoing save, waiting for it to complete");
            while (savingPlayers.contains(uuid)) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logDebug("Interrupted while waiting for save to complete for player " + playerName);
                    break;
                }
            }
            logDebug("Save completed for player " + playerName + ", proceeding with data load");
        }

        // Check if we have cached data (from a failed save)
        PlayerData cachedData = playerDataCache.get(uuid);
        if (cachedData != null) {
            logDebug("Found cached data for player " + playerName + ", attempting to load");
            try {
                if (cachedData.inventoryData != null && !cachedData.inventoryData.isEmpty()) {
                    logDebug("Deserializing cached inventory data for player " + playerName);
                    ItemStack[] items = SerializationUtils.deserializeItemStackArray(cachedData.inventoryData);
                    validateItems(items);
                    inventory.setContents(items);
                    logDebug("Successfully loaded cached inventory data for player " + playerName);
                } else {
                    logDebug("No cached inventory data found for player " + playerName);
                }

                if (cachedData.armorData != null && !cachedData.armorData.isEmpty()) {
                    logDebug("Deserializing cached armor data for player " + playerName);
                    ItemStack[] armor = SerializationUtils.deserializeItemStackArray(cachedData.armorData);
                    validateItems(armor);
                    inventory.setArmorContents(armor);
                    logDebug("Successfully loaded cached armor data for player " + playerName);
                } else {
                    logDebug("No cached armor data found for player " + playerName);
                }

                plugin.getLogger().info("Loaded cached data for player " + playerName);
                return;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load cached data for " + playerName + ", falling back to database");
                logDebug("Error loading cached data for player " + playerName + ": " + e.getMessage());
            }
        } else {
            logDebug("No cached data found for player " + playerName + ", loading from database");
        }

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT inventory, armor FROM player_data_info WHERE uuid=?")) {

            logDebug("Querying database for player " + playerName + " data");
            stmt.setString(1, uuid.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    logDebug("Found database record for player " + playerName);
                    String inventoryData = rs.getString("inventory");
                    String armorData = rs.getString("armor");

                    if (inventoryData != null && !inventoryData.isEmpty()) {
                        logDebug("Deserializing inventory data for player " + playerName);
                        try {
                            ItemStack[] items = SerializationUtils.deserializeItemStackArray(inventoryData);
                            validateItems(items);
                            inventory.setContents(items);
                            logDebug("Successfully loaded inventory data for player " + playerName);
                        } catch (Exception e) {
                            plugin.getLogger().warning("Corrupted inventory data for " + playerName);
                            logDebug("Error deserializing inventory data for player " + playerName + ": " + e.getMessage());
                        }
                    } else {
                        logDebug("No inventory data found in database for player " + playerName);
                    }

                    if (armorData != null && !armorData.isEmpty()) {
                        logDebug("Deserializing armor data for player " + playerName);
                        try {
                            ItemStack[] armor = SerializationUtils.deserializeItemStackArray(armorData);
                            validateItems(armor);
                            inventory.setArmorContents(armor);
                            logDebug("Successfully loaded armor data for player " + playerName);
                        } catch (Exception e) {
                            plugin.getLogger().warning("Corrupted armor data for " + playerName);
                            logDebug("Error deserializing armor data for player " + playerName + ": " + e.getMessage());
                        }
                    } else {
                        logDebug("No armor data found in database for player " + playerName);
                    }
                } else {
                    logDebug("No database record found for player " + playerName + ", using empty inventory");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            plugin.getLogger().severe("Failed to load player data for " + playerName);
            logDebug("Database error while loading data for player " + playerName + ": " + e.getMessage());
        }

        logDebug("Finished loading data for player " + playerName);
    }

    private void validateItems(ItemStack[] items) {
        if (items == null) return;

        int validatedCount = 0;
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item != null) {
                // Check for invalid stack size
                if (item.getAmount() > item.getMaxStackSize()) {
                    logDebug("Found item with invalid stack size: " + item.getType() + " x" + item.getAmount() + " (max: " + item.getMaxStackSize() + "), correcting");
                    item.setAmount(item.getMaxStackSize());
                }

                // Check for other potential issues here if needed

                validatedCount++;
            }
        }
        logDebug("Validated " + validatedCount + " items out of " + items.length + " total slots");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        savePlayerData(event.getPlayer().getUniqueId(), event.getPlayer().getInventory());
    }

    public void savePlayerData(UUID uuid, PlayerInventory inventory) {
        // Skip if already saving this player's data
        if (!savingPlayers.add(uuid)) {
            logDebug("Already saving data for player " + uuid + ", skipping");
            return;
        }

        logDebug("Starting save for player " + uuid);

        // Cache the data in case of failure
        String inventoryData = SerializationUtils.serializeItemStackArray(inventory.getContents());
        String armorData = SerializationUtils.serializeItemStackArray(inventory.getArmorContents());
        playerDataCache.put(uuid, new PlayerData(inventoryData, armorData));
        logDebug("Cached data for player " + uuid + " (inventory size: " + inventory.getContents().length + ", armor size: " + inventory.getArmorContents().length + ")");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            logDebug("Running async save task for player " + uuid);
            try {
                int attempts = 0;
                boolean success = false;

                while (attempts < maxRetryAttempts && !success) {
                    try (Connection conn = plugin.getDatabaseManager().getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "REPLACE INTO player_data_info (uuid, inventory, armor) VALUES (?, ?, ?)")) {

                        logDebug("Attempt " + (attempts + 1) + " to save data for player " + uuid);
                        stmt.setString(1, uuid.toString());
                        stmt.setString(2, inventoryData);
                        stmt.setString(3, armorData);
                        stmt.executeUpdate();

                        success = true;
                        playerDataCache.remove(uuid); // Success, remove from cache
                        logDebug("Successfully saved data for player " + uuid);
                    } catch (Exception e) {
                        attempts++;
                        if (attempts >= maxRetryAttempts) {
                            e.printStackTrace();
                            plugin.getLogger().severe("Failed to save player data for UUID: " + uuid + " after " + maxRetryAttempts + " attempts!");
                            plugin.getLogger().severe("Error: " + e.getMessage());
                            logDebug("Save failed after " + maxRetryAttempts + " attempts for player " + uuid + ". Error: " + e.getMessage());
                            // Keep in cache for manual recovery
                        } else {
                            plugin.getLogger().warning("Failed to save player data for UUID: " + uuid + ", attempt " + attempts + " of " + maxRetryAttempts + ". Retrying...");
                            logDebug("Save attempt " + attempts + " failed for player " + uuid + ". Error: " + e.getMessage() + ". Retrying in " + (retryDelayMs / 1000.0) + " seconds...");
                            try {
                                Thread.sleep(retryDelayMs); // Wait before retry
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                logDebug("Interrupted while waiting to retry save for player " + uuid);
                                break;
                            }
                        }
                    }
                }
            } finally {
                savingPlayers.remove(uuid);
                logDebug("Finished save process for player " + uuid + " (removed from savingPlayers set)");
            }
        });
    }

    @EventHandler
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getPlayer() instanceof org.bukkit.entity.Player) {
            org.bukkit.entity.Player player = (org.bukkit.entity.Player) event.getPlayer();
            savePlayerData(player.getUniqueId(), player.getInventory());
        }
    }

    @EventHandler
    public void onItemDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        // Save after a delay to batch multiple drops
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            savePlayerData(event.getPlayer().getUniqueId(), event.getPlayer().getInventory());
        }, 20L); // 1 second delay
    }

    @EventHandler
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof org.bukkit.entity.Player) {
            org.bukkit.entity.Player player = (org.bukkit.entity.Player) event.getWhoClicked();
            // Save after a delay to batch multiple clicks
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                savePlayerData(player.getUniqueId(), player.getInventory());
            }, 20L); // 1 second delay
        }
    }

    @EventHandler
    public void onItemPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Player) {
            org.bukkit.entity.Player player = (org.bukkit.entity.Player) event.getEntity();
            // Save after a delay to batch multiple pickups
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                savePlayerData(player.getUniqueId(), player.getInventory());
            }, 20L); // 1 second delay
        }
    }

    @EventHandler
    public void onCraftItem(org.bukkit.event.inventory.CraftItemEvent event) {
        if (event.getWhoClicked() instanceof org.bukkit.entity.Player) {
            org.bukkit.entity.Player player = (org.bukkit.entity.Player) event.getWhoClicked();
            // Save after a delay to batch multiple crafts
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                savePlayerData(player.getUniqueId(), player.getInventory());
            }, 20L); // 1 second delay
        }
    }

    @EventHandler
    public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        // Save player data after death (inventory will be empty or modified based on keepInventory gamerule)
        // This ensures we capture the state after death
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            savePlayerData(event.getEntity().getUniqueId(), event.getEntity().getInventory());
        }, 5L); // Short delay to ensure death processing is complete
    }

    @EventHandler
    public void onPlayerRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        // Save player data after respawn (inventory might be restored based on keepInventory gamerule)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            savePlayerData(event.getPlayer().getUniqueId(), event.getPlayer().getInventory());
        }, 5L); // Short delay to ensure respawn processing is complete
    }

    @EventHandler
    public void onItemConsume(org.bukkit.event.player.PlayerItemConsumeEvent event) {
        // Save player data after consuming an item (like potions, food)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            savePlayerData(event.getPlayer().getUniqueId(), event.getPlayer().getInventory());
        }, 5L); // Short delay to ensure item consumption is complete
    }

    @EventHandler
    public void onItemBreak(org.bukkit.event.player.PlayerItemBreakEvent event) {
        // Save player data after an item breaks (like tools, armor)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            savePlayerData(event.getPlayer().getUniqueId(), event.getPlayer().getInventory());
        }, 5L); // Short delay to ensure item break processing is complete
    }

    @EventHandler
    public void onSwapHandItems(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        // Save player data after swapping items between main hand and off hand
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            savePlayerData(event.getPlayer().getUniqueId(), event.getPlayer().getInventory());
        }, 5L); // Short delay to ensure hand swap processing is complete
    }
}
