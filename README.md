
# PlayerDataPlugin

A Minecraft plugin designed to handle player inventory and armor data using a MySQL database for persistent storage. This plugin ensures that players' data is saved and restored seamlessly, even across server restarts or reloads.

---

## Features

1. **Persistent Player Data**  
   - Saves player inventory and armor to a MySQL database.
   - Automatically restores player data upon login.

2. **Scheduled Data Backup**  
   - Periodically saves all online players' data every 5 minutes.

3. **Configurable Database Connection**  
   - Connects to a MySQL database using customizable settings in `config.yml`.

4. **Asynchronous Operations**  
   - Database interactions are performed asynchronously to avoid server lag.

---

## Configuration

The plugin uses a `config.yml` file to define database connection settings. Example configuration:

```yaml
database:
  host: "host"
  port: "port"
  name: "database name"
  user: "username"
  password: "password"
```

### Configuration Details
- **`host`**: The MySQL server's hostname or IP address.
- **`port`**: The port MySQL is running on.
- **`name`**: The name of the database to connect to.
- **`user`**: The username for the database connection.
- **`password`**: The password for the database connection.

---

## Installation

1. Place the plugin JAR file into your server's `plugins` folder.
2. Start or reload your server to generate the default configuration file (`config.yml`).
3. Update `config.yml` with your database connection details.
4. Restart your server to apply the configuration.

---

## How It Works

1. **Player Join**
   - Clears the player's inventory and armor.
   - Fetches saved data from the database and restores it to the player.

2. **Player Quit**
   - Saves the player's inventory and armor to the database.

3. **Scheduled Save**
   - Every 5 minutes, all online players' data is saved to the database.

4. **Database Table**
   - The plugin creates a table `player_data` in the MySQL database:
     ```sql
     CREATE TABLE IF NOT EXISTS player_data (
         uuid VARCHAR(36) PRIMARY KEY,
         inventory TEXT,
         armor TEXT
     );
     ```

---

## Serialization

The plugin uses `SerializationUtils` to:
- Convert player inventory and armor into a Base64 string for storage in the database.
- Deserialize stored Base64 strings back into `ItemStack[]` for restoration.

---

## Example Usage

1. **Player Login**
   - A player logs in, and their saved inventory and armor are loaded from the database.

2. **Player Logout**
   - A player's inventory and armor are saved to the database upon logging out.

3. **Server Restart**
   - The plugin ensures all online players' data is saved before the server shuts down.

---

## Support

If you encounter issues or have suggestions for improvements, feel free to open an issue in the repository or contact the developer.
