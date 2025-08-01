package com.maks.playerdataplugin;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * Handles storage and distribution of the first join kit.
 */
public class FirstJoinKitManager {

    public static final String INVENTORY_TITLE = "First Join Kit Editor";

    private final Main plugin;

    public FirstJoinKitManager(Main plugin) {
        this.plugin = plugin;
    }

    /**
     * Load the kit contents from the database.
     */
    public ItemStack[] loadKit() {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT contents FROM first_join_kit WHERE id=1");
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                String data = rs.getString("contents");
                if (data != null && !data.isEmpty()) {
                    return SerializationUtils.deserializeItemStackArray(data);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load first join kit: " + e.getMessage());
        }
        return new ItemStack[0];
    }

    /**
     * Save the kit contents to the database.
     */
    public void saveKit(ItemStack[] items) {
        String data = SerializationUtils.serializeItemStackArray(items);
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement("REPLACE INTO first_join_kit (id, contents) VALUES (1, ?)");
        ) {
            stmt.setString(1, data);
            stmt.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save first join kit: " + e.getMessage());
        }
    }

    /**
     * Give the kit to a player if they are joining for the first time.
     */
    public void giveKitIfFirstJoin(Player player) {
        UUID uuid = player.getUniqueId();
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            try (PreparedStatement check = conn.prepareStatement("SELECT 1 FROM first_join_players WHERE uuid=?")) {
                check.setString(1, uuid.toString());
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return; // Already received
                    }
                }
            }

            ItemStack[] kit = loadKit();
            if (kit != null && kit.length > 0) {
                player.getInventory().addItem(kit);
            }

            try (PreparedStatement insert = conn.prepareStatement("INSERT INTO first_join_players (uuid) VALUES (?)")) {
                insert.setString(1, uuid.toString());
                insert.executeUpdate();
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to give first join kit to " + player.getName() + ": " + e.getMessage());
        }
    }
}
