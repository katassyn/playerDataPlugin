package com.maks.playerdataplugin;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class DatabaseManager {

    private final Main plugin;
    private HikariDataSource dataSource;
    private final ReentrantLock connectionLock = new ReentrantLock();
    private volatile boolean isShuttingDown = false;

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
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=false&allowPublicKeyRetrieval=true&autoReconnect=true&failOverReadOnly=false&maxReconnects=10");
        config.setUsername(user);
        config.setPassword(password);

        // Enhanced HikariCP settings for stability
        config.setMaximumPoolSize(20);  // Increased for high load
        config.setMinimumIdle(10);      // More idle connections
        config.setIdleTimeout(300000);  // 5 minutes
        config.setConnectionTimeout(30000); // 30 seconds - more time for connection
        config.setMaxLifetime(1800000); // 30 minutes
        config.setLeakDetectionThreshold(60000); // Detect connection leaks
        config.setConnectionTestQuery("SELECT 1"); // Test connections
        config.setValidationTimeout(5000); // 5 seconds validation timeout

        // Enhanced caching
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "500");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("useLocalSessionState", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("cacheResultSetMetadata", "true");
        config.addDataSourceProperty("cacheServerConfiguration", "true");
        config.addDataSourceProperty("elideSetAutoCommits", "true");
        config.addDataSourceProperty("maintainTimeStats", "false");

        try {
            dataSource = new HikariDataSource(config);
            createTables();
            plugin.getLogger().info("Connected to the database using HikariCP with enhanced settings.");
        } catch (SQLException e) {
            e.printStackTrace();
            plugin.getLogger().severe("Failed to connect to the database.");
        }
    }

    public void disconnect() {
        isShuttingDown = true;

        // Wait for ongoing operations with timeout
        try {
            if (!connectionLock.tryLock(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Could not acquire lock for shutdown, forcing disconnect");
            }

            if (dataSource != null && !dataSource.isClosed()) {
                // Close all connections gracefully
                dataSource.close();
                plugin.getLogger().info("Disconnected from the database.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().severe("Interrupted while disconnecting from database");
        } finally {
            if (connectionLock.isHeldByCurrentThread()) {
                connectionLock.unlock();
            }
        }
    }

    private void createTables() throws SQLException {
        // Create player_data_info table with versioning
        String playerDataSql = "CREATE TABLE IF NOT EXISTS player_data_info (" +
                "uuid VARCHAR(36) PRIMARY KEY," +
                "inventory MEDIUMTEXT," +  // MEDIUMTEXT for larger inventories
                "armor MEDIUMTEXT," +
                "version INT DEFAULT 0," +  // Version for optimistic locking
                "last_saved TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "checksum VARCHAR(64)," +  // Data integrity check
                "INDEX idx_uuid (uuid)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";  // InnoDB for transactions

        // Create backup table for recovery
        String backupSql = "CREATE TABLE IF NOT EXISTS player_data_backup (" +
                "id INT AUTO_INCREMENT PRIMARY KEY," +
                "uuid VARCHAR(36)," +
                "inventory MEDIUMTEXT," +
                "armor MEDIUMTEXT," +
                "backup_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "reason VARCHAR(50)," +
                "INDEX idx_uuid_time (uuid, backup_time)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

        // Create player_stats table
        String playerStatsSql = "CREATE TABLE IF NOT EXISTS player_stats (" +
                "uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                "username VARCHAR(16)," +
                "mobs_killed INT DEFAULT 0," +
                "players_killed INT DEFAULT 0," +
                "deaths INT DEFAULT 0," +
                "playtime_hours DOUBLE DEFAULT 0.0," +
                "balance DOUBLE DEFAULT 0.0," +
                "version INT DEFAULT 0," +
                "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "INDEX idx_username (username)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

        // Table for storing first join kit contents
        String firstJoinKitSql = "CREATE TABLE IF NOT EXISTS first_join_kit (" +
                "id INT PRIMARY KEY," +
                "contents MEDIUMTEXT" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

        // Table for tracking players who already received the kit
        String firstJoinPlayersSql = "CREATE TABLE IF NOT EXISTS first_join_players (" +
                "uuid VARCHAR(36) PRIMARY KEY," +
                "received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

        // Transaction log for debugging
        String transactionLogSql = "CREATE TABLE IF NOT EXISTS transaction_log (" +
                "id INT AUTO_INCREMENT PRIMARY KEY," +
                "uuid VARCHAR(36)," +
                "action VARCHAR(50)," +
                "status VARCHAR(20)," +
                "details TEXT," +
                "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "INDEX idx_uuid_timestamp (uuid, timestamp)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Create all tables
                executeStatement(connection, playerDataSql, "Player data table");
                executeStatement(connection, backupSql, "Backup table");
                executeStatement(connection, playerStatsSql, "Player stats table");
                executeStatement(connection, firstJoinKitSql, "First join kit table");
                executeStatement(connection, firstJoinPlayersSql, "First join players table");
                executeStatement(connection, transactionLogSql, "Transaction log table");

                connection.commit();
                plugin.getLogger().info("All tables created/verified successfully.");
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private void executeStatement(Connection conn, String sql, String tableName) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.execute();
            plugin.getLogger().info(tableName + " created/verified.");
        }
    }

    public Connection getConnection() throws SQLException {
        if (isShuttingDown) {
            throw new SQLException("Database is shutting down");
        }

        Connection conn = dataSource.getConnection();
        // Set transaction isolation level for consistency
        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        return conn;
    }

    // Safe transaction execution with automatic retry
    public <T> CompletableFuture<T> executeTransaction(TransactionCallback<T> callback) {
        return CompletableFuture.supplyAsync(() -> {
            int attempts = 0;
            int maxAttempts = 5;
            long backoffMs = 100;

            while (attempts < maxAttempts && !isShuttingDown) {
                attempts++;
                Connection conn = null;

                try {
                    conn = getConnection();
                    conn.setAutoCommit(false);

                    T result = callback.execute(conn);

                    conn.commit();
                    return result;

                } catch (SQLException e) {
                    if (conn != null) {
                        try {
                            conn.rollback();
                        } catch (SQLException rollbackEx) {
                            plugin.getLogger().severe("Failed to rollback transaction: " + rollbackEx.getMessage());
                        }
                    }

                    if (attempts >= maxAttempts) {
                        plugin.getLogger().severe("Transaction failed after " + maxAttempts + " attempts: " + e.getMessage());
                        throw new RuntimeException(e);
                    }

                    // Exponential backoff
                    try {
                        Thread.sleep(backoffMs * attempts);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }

                } finally {
                    if (conn != null) {
                        try {
                            conn.close();
                        } catch (SQLException e) {
                            plugin.getLogger().warning("Failed to close connection: " + e.getMessage());
                        }
                    }
                }
            }

            throw new RuntimeException("Failed to execute transaction - server is shutting down");
        });
    }

    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }

    public interface TransactionCallback<T> {
        T execute(Connection conn) throws SQLException;
    }
}