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

public class PlayerDataListener implements Listener {

    private final Main plugin;

    public PlayerDataListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        PlayerInventory inventory = event.getPlayer().getInventory();

        // Clear the inventory to prevent default items
        inventory.clear();
        inventory.setArmorContents(null);

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT inventory, armor FROM player_data_info WHERE uuid=?")) {

            stmt.setString(1, uuid.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String inventoryData = rs.getString("inventory");
                    String armorData = rs.getString("armor");

                    if (inventoryData != null && !inventoryData.isEmpty()) {
                        ItemStack[] items = SerializationUtils.deserializeItemStackArray(inventoryData);
                        inventory.setContents(items);
                    }

                    if (armorData != null && !armorData.isEmpty()) {
                        ItemStack[] armor = SerializationUtils.deserializeItemStackArray(armorData);
                        inventory.setArmorContents(armor);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            plugin.getLogger().severe("Failed to load player data for " + event.getPlayer().getName());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        savePlayerData(event.getPlayer().getUniqueId(), event.getPlayer().getInventory());
    }

    public void savePlayerData(UUID uuid, PlayerInventory inventory) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "REPLACE INTO player_data_info (uuid, inventory, armor) VALUES (?, ?, ?)")) {

                stmt.setString(1, uuid.toString());

                String inventoryData = SerializationUtils.serializeItemStackArray(inventory.getContents());
                String armorData = SerializationUtils.serializeItemStackArray(inventory.getArmorContents());

                stmt.setString(2, inventoryData);
                stmt.setString(3, armorData);
                stmt.executeUpdate();

            } catch (Exception e) {
                e.printStackTrace();
                plugin.getLogger().severe("Failed to save player data for UUID: " + uuid);
            }
        });
    }
}