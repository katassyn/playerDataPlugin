
# PlayerDataPlugin

A Minecraft plugin designed to handle player inventory, armor data, and player statistics using a MySQL database for persistent storage. This plugin ensures that players' data is saved and restored seamlessly, even across server restarts or reloads.

---

## Features

1. **Persistent Player Data**  
   - Saves player inventory and armor to a MySQL database.
   - Automatically restores player data upon login.

2. **Player Statistics Tracking**  
   - Tracks player statistics including:
     - Mobs killed
     - Players killed
     - Deaths
     - Playtime hours
     - Balance (requires Vault)
   - Calculates K/D ratio
   - View stats with `/stats` command

3. **Scheduled Data Backup**  
   - Periodically saves all online players' data and statistics.
   - Configurable save intervals for both inventory and stats.

4. **Configurable Database Connection**  
   - Connects to a MySQL database using customizable settings in `config.yml`.

5. **Asynchronous Operations**  
   - Database interactions are performed asynchronously to avoid server lag.

6. **Economy Integration**  
   - Optional integration with Vault for tracking player balance.

---

## Configuration

The plugin uses a `config.yml` file to define database connection settings and other options. Example configuration:

```yaml
database:
  host: "host"
  port: "port"
  name: "database name"
  user: "username"
  password: "password"

saveInterval:
  ticks: 1200        # How often to save inventory data (1200 ticks = 1 minute)
  batchSizePercent: 20  # Percentage of online players to save per batch

statsInterval:
  ticks: 6000        # How often to save player statistics (6000 ticks = 5 minutes)

debug: false         # Enable debug logging
```

### Configuration Details
- **Database Settings**:
  - **`host`**: The MySQL server's hostname or IP address.
  - **`port`**: The port MySQL is running on.
  - **`name`**: The name of the database to connect to.
  - **`user`**: The username for the database connection.
  - **`password`**: The password for the database connection.

- **Save Intervals**:
  - **`saveInterval.ticks`**: How often to save inventory data (in server ticks, 20 ticks = 1 second).
  - **`saveInterval.batchSizePercent`**: Percentage of online players to save in each batch (for performance).
  - **`statsInterval.ticks`**: How often to save player statistics (in server ticks).

- **Debug Mode**:
  - **`debug`**: When set to true, enables detailed logging for troubleshooting.

---

## Installation

1. Place the plugin JAR file into your server's `plugins` folder.
2. Start or reload your server to generate the default configuration file (`config.yml`).
3. Update `config.yml` with your database connection details.
4. Restart your server to apply the configuration.

---

## Commands and Permissions

### Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/stats` | View your own statistics | `playerdataplugin.stats` |
| `/stats <player>` | View another player's statistics | `playerdataplugin.stats.others` |
| `/stats reload <player>` | Reload a player's statistics | `playerdataplugin.stats.reload` |

### Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `playerdataplugin.stats` | Allows viewing own statistics | All players |
| `playerdataplugin.stats.others` | Allows viewing other players' statistics | Operators |
| `playerdataplugin.stats.reload` | Allows reloading player statistics | Operators |
| `playerdataplugin.admin` | Gives access to all plugin features | Operators |

---

## How It Works

### Inventory Management

1. **Player Join**
   - Clears the player's inventory and armor.
   - Fetches saved data from the database and restores it to the player.

2. **Player Quit**
   - Saves the player's inventory and armor to the database.

3. **Scheduled Save**
   - Periodically saves online players' inventory data to the database.
   - Uses a batch system to distribute database operations for better performance.

4. **Inventory Database Table**
   - The plugin creates a table `player_data` in the MySQL database:
     ```sql
     CREATE TABLE IF NOT EXISTS player_data (
         uuid VARCHAR(36) PRIMARY KEY,
         inventory TEXT,
         armor TEXT
     );
     ```

### Statistics Tracking

1. **Player Join**
   - Loads player statistics from the database.
   - Starts tracking playtime.

2. **Player Quit**
   - Stops playtime tracking and calculates session duration.
   - Saves all statistics to the database.

3. **Statistics Events**
   - Tracks mob kills, player kills, and deaths through event listeners.
   - Updates statistics in real-time.

4. **Economy Integration**
   - If Vault is installed, tracks player balance.
   - Updates balance when viewing statistics.

5. **Scheduled Stats Save**
   - Periodically saves all online players' statistics to the database.

6. **Statistics Database Table**
   - The plugin creates a table `player_stats` in the MySQL database:
     ```sql
     CREATE TABLE IF NOT EXISTS player_stats (
         uuid VARCHAR(36) NOT NULL PRIMARY KEY,
         username VARCHAR(16),
         mobs_killed INT DEFAULT 0,
         players_killed INT DEFAULT 0,
         deaths INT DEFAULT 0,
         playtime_hours DOUBLE DEFAULT 0.0,
         balance DOUBLE DEFAULT 0.0,
         last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
     );
     ```

---

## Serialization

The plugin uses `SerializationUtils` to:
- Convert player inventory and armor into a Base64 string for storage in the database.
- Deserialize stored Base64 strings back into `ItemStack[]` for restoration.

---

## Example Usage

### Inventory Management

1. **Player Login**
   - A player logs in, and their saved inventory and armor are loaded from the database.

2. **Player Logout**
   - A player's inventory and armor are saved to the database upon logging out.

3. **Server Restart**
   - The plugin ensures all online players' data is saved before the server shuts down.

### Player Statistics

1. **Viewing Statistics**
   - A player types `/stats` to view their own statistics.
   - An admin types `/stats PlayerName` to view another player's statistics.

2. **Tracking Progress**
   - A player kills mobs and other players, and their statistics are updated in real-time.
   - Playtime is tracked automatically while the player is online.

3. **Economy Integration**
   - If Vault is installed, the player's balance is displayed in their statistics.

4. **Offline Player Lookup**
   - An admin can look up statistics for offline players using `/stats PlayerName`.

---

## Support

If you encounter issues or have suggestions for improvements, feel free to open an issue in the repository or contact the developer.
