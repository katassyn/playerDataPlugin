package com.maks.playerdataplugin;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class DatabaseManager {

    private final Main plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(Main plugin) {
        this.plugin = plugin;
    }

    public void connect() {
        String host = plugin.getConfig().getString("database.host");
        String port = plugin.getConfig().getString("database.port");
        String database = plugin.getConfig().getString("database.name");
        String user = plugin.getConfig().getString("database.user");
        String password = plugin.getConfig().getString("database.password");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true");
        config.setUsername(user);
        config.setPassword(password);

        // HikariCP settings
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(5);
        config.setIdleTimeout(300000); // 5 minutes
        config.setConnectionTimeout(10000); // 10 seconds
        config.setMaxLifetime(1800000); // 30 minutes
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        try {
            dataSource = new HikariDataSource(config);
            createTable();
            plugin.getLogger().info("Connected to the database using HikariCP.");
        } catch (SQLException e) {
            e.printStackTrace();
            plugin.getLogger().severe("Failed to connect to the database.");
        }
    }

    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Disconnected from the database.");
        }
    }

    private void createTable() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS player_data_info (" +
                "uuid VARCHAR(36) PRIMARY KEY," +
                "inventory TEXT," +
                "armor TEXT" +
                ");";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
}