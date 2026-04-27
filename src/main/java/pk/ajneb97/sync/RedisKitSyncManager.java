package pk.ajneb97.sync;

import pk.ajneb97.PlayerKits2;
import pk.ajneb97.configs.MainConfigManager;
import pk.ajneb97.model.PlayerDataKit;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RedisKitSyncManager {

    private final PlayerKits2 plugin;
    private boolean enabled;
    private String host;
    private int port;
    private int database;
    private String user;
    private String password;
    private boolean useSsl;
    private int timeoutMillis;
    private String keyPrefix;
    private final ConcurrentMap<String, CacheEntry> readCache;

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
            Object result = execute("PING");
            if (!"PONG".equalsIgnoreCase(String.valueOf(result))) {
                enabled = false;
                plugin.getLogger().warning("Redis kit sync disabled: unexpected PING response from Redis.");
                return;
            }
            plugin.getLogger().info("Redis kit sync enabled using " + host + ":" + port + "/" + database + ".");
        } catch (Exception e) {
            enabled = false;
            plugin.getLogger().warning("Redis kit sync disabled: " + e.getMessage());
        }
    }

    public void shutdown() {
        // Connections are short-lived per command.
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<PlayerDataKit> getKit(UUID uuid, String kitName) {
        if (!enabled) {
            return Optional.empty();
        }

        try {
            String cacheKey = key(uuid, kitName);
            CacheEntry cached = readCache.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                return Optional.of(copy(cached.kit));
            }

            Object response = execute("HGETALL", cacheKey);
            if (!(response instanceof List)) {
                return Optional.empty();
            }

            List<?> values = (List<?>) response;
            if (values.isEmpty()) {
                return Optional.empty();
            }

            String name = kitName;
            long cooldown = 0L;
            boolean oneTime = false;
            boolean bought = false;

            for (int i = 0; i + 1 < values.size(); i += 2) {
                String field = String.valueOf(values.get(i));
                String value = String.valueOf(values.get(i + 1));
                if ("name".equals(field)) {
                    name = value;
                } else if ("cooldown".equals(field)) {
                    cooldown = parseLong(value);
                } else if ("one_time".equals(field)) {
                    oneTime = "1".equals(value) || Boolean.parseBoolean(value);
                } else if ("bought".equals(field)) {
                    bought = "1".equals(value) || Boolean.parseBoolean(value);
                }
            }

            PlayerDataKit kit = new PlayerDataKit(name);
            kit.setCooldown(cooldown);
            kit.setOneTime(oneTime);
            kit.setBought(bought);
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

        try {
            execute(
                    "HSET",
                    key(uuid, kit.getName()),
                    "name", kit.getName(),
                    "cooldown", String.valueOf(kit.getCooldown()),
                    "one_time", kit.isOneTime() ? "1" : "0",
                    "bought", kit.isBought() ? "1" : "0",
                    "updated_at", String.valueOf(System.currentTimeMillis())
            );
            readCache.put(key(uuid, kit.getName()), new CacheEntry(copy(kit)));
        } catch (Exception e) {
            plugin.getLogger().warning("Unable to write Redis kit state for " + uuid + "/" + kit.getName() + ": " + e.getMessage());
        }
    }

    public void resetKit(UUID uuid, String kitName) {
        if (!enabled) {
            return;
        }

        try {
            execute("DEL", key(uuid, kitName));
            readCache.remove(key(uuid, kitName));
        } catch (Exception e) {
            plugin.getLogger().warning("Unable to delete Redis kit state for " + uuid + "/" + kitName + ": " + e.getMessage());
        }
    }

    public void resetKitForAllPlayers(String kitName) {
        if (!enabled) {
            return;
        }

        String cursor = "0";
        String pattern = keyPrefix + ":kit:*:" + kitName;
        try {
            do {
                Object response = execute("SCAN", cursor, "MATCH", pattern, "COUNT", "500");
                if (!(response instanceof List)) {
                    return;
                }
                List<?> scan = (List<?>) response;
                if (scan.size() < 2) {
                    return;
                }
                cursor = String.valueOf(scan.get(0));
                Object keysObject = scan.get(1);
                if (keysObject instanceof List) {
                    List<?> keys = (List<?>) keysObject;
                    for (Object key : keys) {
                        execute("DEL", String.valueOf(key));
                        readCache.remove(String.valueOf(key));
                    }
                }
            } while (!"0".equals(cursor));
        } catch (Exception e) {
            plugin.getLogger().warning("Unable to delete Redis kit state for kit " + kitName + ": " + e.getMessage());
        }
    }

    private Object execute(String... command) throws IOException {
        RedisConnection connection = null;
        try {
            connection = connect();
            if (password != null && !password.isEmpty()) {
                if (user != null && !user.isEmpty()) {
                    connection.command("AUTH", user, password);
                } else {
                    connection.command("AUTH", password);
                }
            }
            if (database > 0) {
                connection.command("SELECT", String.valueOf(database));
            }
            return connection.command(command);
        } finally {
            closeQuietly(connection);
        }
    }

    private RedisConnection connect() throws IOException {
        Socket socket = useSsl ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMillis);
        socket.setSoTimeout(timeoutMillis);
        return new RedisConnection(socket);
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
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static PlayerDataKit copy(PlayerDataKit source) {
        PlayerDataKit copy = new PlayerDataKit(source.getName());
        copy.setCooldown(source.getCooldown());
        copy.setOneTime(source.isOneTime());
        copy.setBought(source.isBought());
        return copy;
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

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static final class RedisConnection implements Closeable {

        private final Socket socket;
        private final BufferedInputStream input;
        private final BufferedOutputStream output;

        private RedisConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.input = new BufferedInputStream(socket.getInputStream());
            this.output = new BufferedOutputStream(socket.getOutputStream());
        }

        private Object command(String... parts) throws IOException {
            writeCommand(parts);
            output.flush();
            return readResponse();
        }

        private void writeCommand(String... parts) throws IOException {
            writeAscii("*" + parts.length + "\r\n");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                writeAscii("$" + bytes.length + "\r\n");
                output.write(bytes);
                writeAscii("\r\n");
            }
        }

        private Object readResponse() throws IOException {
            int type = input.read();
            if (type == -1) {
                throw new IOException("Redis closed the connection");
            }

            if (type == '+') {
                return readLine();
            }
            if (type == '-') {
                throw new IOException(readLine());
            }
            if (type == ':') {
                return Long.valueOf(readLine());
            }
            if (type == '$') {
                int length = Integer.parseInt(readLine());
                if (length < 0) {
                    return null;
                }
                byte[] bytes = readBytes(length);
                readCrLf();
                return new String(bytes, StandardCharsets.UTF_8);
            }
            if (type == '*') {
                int length = Integer.parseInt(readLine());
                if (length < 0) {
                    return null;
                }
                List<Object> values = new ArrayList<Object>(length);
                for (int i = 0; i < length; i++) {
                    values.add(readResponse());
                }
                return values;
            }

            throw new IOException("Unsupported Redis response type: " + (char) type);
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int previous = -1;
            int current;
            while ((current = input.read()) != -1) {
                if (previous == '\r' && current == '\n') {
                    byte[] bytes = buffer.toByteArray();
                    return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
                }
                buffer.write(current);
                previous = current;
            }
            throw new IOException("Redis closed the connection while reading a line");
        }

        private byte[] readBytes(int length) throws IOException {
            byte[] bytes = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = input.read(bytes, offset, length - offset);
                if (read == -1) {
                    throw new IOException("Redis closed the connection while reading bulk data");
                }
                offset += read;
            }
            return bytes;
        }

        private void readCrLf() throws IOException {
            int cr = input.read();
            int lf = input.read();
            if (cr != '\r' || lf != '\n') {
                throw new IOException("Invalid Redis line ending");
            }
        }

        private void writeAscii(String value) throws IOException {
            output.write(value.getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
