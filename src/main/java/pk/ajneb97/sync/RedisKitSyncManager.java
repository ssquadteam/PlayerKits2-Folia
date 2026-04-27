package pk.ajneb97.sync;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import pk.ajneb97.PlayerKits2;
import pk.ajneb97.configs.MainConfigManager;
import pk.ajneb97.model.PlayerDataKit;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RedisKitSyncManager {

    private final PlayerKits2 plugin;
    private final ConcurrentMap<String, CacheEntry> readCache;

    private boolean enabled;
    private String host;
    private int port;
    private int database;
    private String user;
    private String password;
    private boolean useSsl;
    private int timeoutMillis;
    private String keyPrefix;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> commands;

    public RedisKitSyncManager(PlayerKits2 plugin) {
        this.plugin = plugin;
        this.readCache = new ConcurrentHashMap<String, CacheEntry>();
    }

    public void start() {
        MainConfigManager config = plugin.getConfigsManager().getMainConfigManager();
        this.enabled = config.isRedisSyncEnabled();
        this.host = config.getRedisSyncHost();
        this.port = config.getRedisSyncPort();
        this.database = config.getRedisSyncDatabase();
        this.user = config.getRedisSyncUser();
        this.password = config.getRedisSyncPassword();
        this.useSsl = config.isRedisSyncUseSsl();
        this.timeoutMillis = Math.max(250, config.getRedisSyncTimeoutMillis());
        this.keyPrefix = normalizePrefix(config.getRedisSyncKeyPrefix());

        if (!enabled) {
            return;
        }

        try {
            RedisURI.Builder builder = RedisURI.Builder.redis(host, port)
                    .withDatabase(database)
                    .withSsl(useSsl)
                    .withTimeout(Duration.ofMillis(timeoutMillis));

            if (password != null && !password.isEmpty()) {
                if (user != null && !user.isEmpty()) {
                    builder.withAuthentication(user, password.toCharArray());
                } else {
                    builder.withPassword(password.toCharArray());
                }
            }

            client = RedisClient.create(builder.build());
            connection = client.connect();
            commands = connection.sync();

            String response = commands.ping();
            if (!"PONG".equalsIgnoreCase(response)) {
                disable("unexpected PING response from Redis");
                return;
            }

            plugin.getLogger().info("Redis kit sync enabled using " + host + ":" + port + "/" + database + ".");
        } catch (Exception e) {
            disable(e.getMessage());
        }
    }

    public void shutdown() {
        closeQuietly(connection);
        if (client != null) {
            client.shutdown();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<PlayerDataKit> getKit(UUID uuid, String kitName) {
        if (!enabled) {
            return Optional.empty();
        }

        String cacheKey = key(uuid, kitName);
        CacheEntry cached = readCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return Optional.of(copy(cached.kit));
        }

        try {
            Map<String, String> values = commands.hgetall(cacheKey);
            if (values.isEmpty()) {
                return Optional.empty();
            }

            PlayerDataKit kit = new PlayerDataKit(values.containsKey("name") ? values.get("name") : kitName);
            kit.setCooldown(parseLong(values.get("cooldown")));
            kit.setOneTime(parseBoolean(values.get("one_time")));
            kit.setBought(parseBoolean(values.get("bought")));

            readCache.put(cacheKey, new CacheEntry(copy(kit)));
            return Optional.of(kit);
        } catch (Exception e) {
            plugin.getLogger().warning("Unable to read Redis kit state for " + uuid + "/" + kitName + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    public void saveKit(UUID uuid, PlayerDataKit kit) {
        if (!enabled) {
            return;
        }

        String redisKey = key(uuid, kit.getName());
        try {
            Map<String, String> values = new HashMap<String, String>();
            values.put("name", kit.getName());
            values.put("cooldown", String.valueOf(kit.getCooldown()));
            values.put("one_time", kit.isOneTime() ? "1" : "0");
            values.put("bought", kit.isBought() ? "1" : "0");
            values.put("updated_at", String.valueOf(System.currentTimeMillis()));

            commands.hset(redisKey, values);
            readCache.put(redisKey, new CacheEntry(copy(kit)));
        } catch (Exception e) {
            plugin.getLogger().warning("Unable to write Redis kit state for " + uuid + "/" + kit.getName() + ": " + e.getMessage());
        }
    }

    public void resetKit(UUID uuid, String kitName) {
        if (!enabled) {
            return;
        }

        String redisKey = key(uuid, kitName);
        try {
            commands.del(redisKey);
            readCache.remove(redisKey);
        } catch (Exception e) {
            plugin.getLogger().warning("Unable to delete Redis kit state for " + uuid + "/" + kitName + ": " + e.getMessage());
        }
    }

    public void resetKitForAllPlayers(String kitName) {
        if (!enabled) {
            return;
        }

        String pattern = keyPrefix + ":kit:*:" + kitName;
        ScanCursor cursor = ScanCursor.INITIAL;
        ScanArgs args = ScanArgs.Builder.matches(pattern).limit(500);

        try {
            do {
                KeyScanCursor<String> scan = commands.scan(cursor, args);
                for (String key : scan.getKeys()) {
                    commands.del(key);
                    readCache.remove(key);
                }
                cursor = ScanCursor.of(scan.getCursor());
            } while (!cursor.isFinished());
        } catch (Exception e) {
            plugin.getLogger().warning("Unable to delete Redis kit state for kit " + kitName + ": " + e.getMessage());
        }
    }

    private void disable(String reason) {
        enabled = false;
        shutdown();
        plugin.getLogger().warning("Redis kit sync disabled: " + reason);
    }

    private String key(UUID uuid, String kitName) {
        return keyPrefix + ":kit:" + uuid + ":" + kitName;
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return "playerkits2";
        }
        return prefix.trim().toLowerCase(Locale.ROOT);
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static boolean parseBoolean(String value) {
        return "1".equals(value) || Boolean.parseBoolean(value);
    }

    private static PlayerDataKit copy(PlayerDataKit source) {
        PlayerDataKit copy = new PlayerDataKit(source.getName());
        copy.setCooldown(source.getCooldown());
        copy.setOneTime(source.isOneTime());
        copy.setBought(source.isBought());
        return copy;
    }

    private static void closeQuietly(StatefulRedisConnection<String, String> connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception ignored) {
        }
    }

    private static final class CacheEntry {
        private final PlayerDataKit kit;
        private final long expiresAt;

        private CacheEntry(PlayerDataKit kit) {
            this.kit = kit;
            this.expiresAt = System.currentTimeMillis() + 1000L;
        }

        private boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
