# PlayerDataPlugin

A high-performance Minecraft plugin for saving player inventory data to MySQL database instead of player data files. This plugin uses HikariCP for efficient database connection pooling and async operations for optimal server performance.

## Features

- Saves player inventory and armor data to MySQL database
- Uses connection pooling with HikariCP for optimal database performance
- Asynchronous data saving to prevent server lag
- Automatic periodic saving every 5 minutes
- Disables default player data saving to files for better performance
- Safe data loading on player join
- Automatic data saving on player quit

## Requirements

- Java 8 or higher
- MySQL/MariaDB database
- Paper/Spigot 1.20.1 (may work with other versions, but untested)
- Maven (for building)

## Installation

1. Download the latest release or build from source
2. Place the jar file in your server's `plugins` folder
3. Start the server once to generate the config file
4. Configure the database settings in `plugins/PlayerDataPlugin/config.yml`
5. Restart your server

## Configuration

Create `config.yml` in the plugin directory with the following structure:

```yaml
database:
  host: localhost
  port: 3306
  name: your_database_name
  user: your_username
  password: your_password
```

## Building from Source

```bash
git clone https://github.com/yourusername/PlayerDataPlugin.git
cd PlayerDataPlugin
mvn clean package
```

The built jar will be in the `target` directory.

## Database Schema

The plugin uses the following table structure:

```sql
CREATE TABLE IF NOT EXISTS player_data_info (
    uuid VARCHAR(36) PRIMARY KEY,
    inventory TEXT,
    armor TEXT
);
```

## Technical Details

### Connection Pooling
- Uses HikariCP for database connection management
- Configured with optimized pool settings:
  - Maximum pool size: 10 connections
  - Minimum idle connections: 5
  - Connection timeout: 10 seconds
  - Maximum lifetime: 30 minutes
  - Prepared statement caching enabled

### Data Serialization
- Inventory and armor data are serialized using Bukkit's serialization utilities
- Data is stored in Base64 encoded format
- Uses efficient binary serialization for ItemStack objects

### Performance Considerations
- Asynchronous saving operations
- Connection pooling for optimal database performance
- Periodic saving to prevent data loss
- Proper resource cleanup and connection handling

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

If you encounter any issues or have questions, please create an issue on the GitHub repository.

## Authors

- Your Name - *Initial work*

## Acknowledgments

- PaperMC Team for the amazing server software
- HikariCP for the excellent connection pooling library
