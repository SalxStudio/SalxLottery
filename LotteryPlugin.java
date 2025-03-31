import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import me.clip.placeholderapi.PlaceholderAPI;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;

// 抽奖插件主类，继承自 JavaPlugin
public class LotteryPlugin extends JavaPlugin {

    // 配置文件对象
    private FileConfiguration config;
    // 语言文件配置
    private Map<String, FileConfiguration> languageConfigs;
    // 随机数生成器
    private Random random;
    // Vault 权限管理对象
    private Permission permission;
    // 数据库连接
    private Connection connection;
    // 存储方式，sqlite 或 mysql
    private String storageType;

    @Override
    public void onEnable() {
        // 保存默认配置文件，如果不存在则创建
        saveDefaultConfig();
        // 获取配置文件对象
        config = getConfig();
        // 初始化随机数生成器
        random = new Random();
        // 加载语言文件
        loadLanguageConfigs();
        // 检查 Vault 插件是否已安装
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            permission = getServer().getServicesManager().getRegistration(Permission.class).getProvider();
        }
        // 检查 PlaceholderAPI 插件是否已安装
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            // 注册 PlaceholderAPI 钩子
            new PlaceholderHook(this).register();
        }
        // 初始化数据库连接
        initDatabase();
        // 创建必要的数据库表
        createTables();
        // 输出插件启用信息到控制台
        getLogger().info("Lottery plugin has been enabled!");
    }

    @Override
    public void onDisable() {
        // 关闭数据库连接
        closeDatabaseConnection();
        // 输出插件禁用信息到控制台
        getLogger().info("Lottery plugin has been disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // 检查命令是否为 /lottery 且发送者为玩家
        if (cmd.getName().equalsIgnoreCase("lottery") && sender instanceof Player) {
            Player player = (Player) sender;
            // 检查玩家是否可以抽奖
            if (canPlayerLottery(player)) {
                // 播放抽奖音效
                playLotterySound(player);
                // 播放抽奖粒子特效
                playLotteryParticles(player);
                // 模拟屏幕震动
                simulateScreenShake(player);
                // 执行抽奖操作
                performLottery(player);
                // 记录玩家抽奖次数
                recordLotteryCount(player);
                // 记录玩家上次抽奖时间
                recordLastLotteryTime(player);
            }
            return true;
        }
        return false;
    }

    private boolean canPlayerLottery(Player player) {
        UUID playerUUID = player.getUniqueId();
        // 检查抽奖次数限制是否开启
        boolean lotteryCountLimitEnabled = config.getBoolean("lottery-count-limit-enabled", false);
        if (lotteryCountLimitEnabled) {
            int maxLotteryCount = getMaxLotteryCount(player);
            if (maxLotteryCount > 0) {
                int currentCount = getPlayerLotteryCount(playerUUID);
                if (currentCount >= maxLotteryCount) {
                    sendMessage(player, "lottery-count-limit-reached");
                    return false;
                }
            }
        }
        // 检查抽奖冷却时间
        long cooldown = config.getLong("lottery-cooldown", 0) * 1000;
        if (cooldown > 0) {
            long lastLotteryTime = getPlayerLastLotteryTime(playerUUID);
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastLotteryTime < cooldown) {
                long remainingTime = (cooldown - (currentTime - lastLotteryTime)) / 1000;
                sendMessage(player, "lottery-cooldown-remaining", String.valueOf(remainingTime));
                return false;
            }
        }
        // 检查是否处于保底状态
        boolean isGuarantee = isPlayerInGuarantee(playerUUID);
        if (isGuarantee) {
            return true;
        }
        return true;
    }

    private void performLottery(Player player) {
        UUID playerUUID = player.getUniqueId();
        // 检查玩家是否处于保底抽奖状态
        boolean isGuarantee = isPlayerInGuarantee(playerUUID);
        if (isGuarantee) {
            // 执行保底抽奖
            performGuaranteeLottery(player);
            // 重置保底状态
            setPlayerGuaranteeStatus(playerUUID, false);
            // 重置抽奖次数
            setPlayerLotteryCount(playerUUID, 0);
            return;
        }

        // 获取玩家权限组对应的奖品列表
        List<Map<?, ?>> prizes = getPrizesForPlayer(player);
        // 存储每个奖品的概率
        List<Double> probabilities = new ArrayList<>();
        // 遍历奖品列表，提取概率信息
        for (Map<?, ?> prize : prizes) {
            probabilities.add(Double.parseDouble(prize.get("probability").toString()));
        }
        // 生成一个 0 到 100 之间的随机数
        double randomValue = random.nextDouble() * 100;
        double cumulativeProbability = 0;
        int winningIndex = -1;
        // 遍历概率列表，根据随机数确定中奖奖品
        for (int i = 0; i < probabilities.size(); i++) {
            cumulativeProbability += probabilities.get(i);
            if (randomValue < cumulativeProbability) {
                winningIndex = i;
                break;
            }
        }
        // 如果中奖
        if (winningIndex != -1) {
            Map<?, ?> winningPrize = prizes.get(winningIndex);
            List<String> commands = (List<String>) winningPrize.get("commands");
            for (String command : commands) {
                // 替换 PlaceholderAPI 占位符
                command = PlaceholderAPI.setPlaceholders(player, command);
                // 执行中奖命令
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
            // 向玩家发送中奖消息
            sendMessage(player, "lottery-win");
            // 展示中奖详情
            showWinningDetails(player, winningPrize);
            // 记录抽奖日志
            recordLotteryLog(player, commands.toString());
        } else {
            // 向玩家发送未中奖消息
            sendMessage(player, "lottery-lose");
            // 记录抽奖日志
            recordLotteryLog(player, "未中奖");
        }

        // 检查是否触发保底
        int currentCount = getPlayerLotteryCount(playerUUID);
        int guaranteeCount = config.getInt("guarantee-count", 10);
        if (currentCount + 1 >= guaranteeCount) {
            // 开启保底抽奖状态
            setPlayerGuaranteeStatus(playerUUID, true);
            sendMessage(player, "lottery-guarantee-triggered");
        }
    }

    private void performGuaranteeLottery(Player player) {
        // 获取保底奖品列表
        List<String> guaranteeCommands = config.getStringList("guarantee-commands");
        for (String command : guaranteeCommands) {
            // 替换 PlaceholderAPI 占位符
            command = PlaceholderAPI.setPlaceholders(player, command);
            // 执行保底中奖命令
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
        // 向玩家发送保底中奖消息
        sendMessage(player, "lottery-guarantee-win");
        // 展示中奖详情
        StringBuilder prizePreview = new StringBuilder();
        for (String command : guaranteeCommands) {
            if (command.startsWith("give")) {
                String[] parts = command.split(" ");
                if (parts.length >= 3) {
                    String itemName = parts[2];
                    int amount = Integer.parseInt(parts[3]);
                    prizePreview.append(itemName).append(" x ").append(amount).append(", ");
                }
            }
        }
        if (prizePreview.length() > 0) {
            prizePreview.setLength(prizePreview.length() - 2);
        }
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String timestamp = dateFormat.format(new Date());
        sendMessage(player, "lottery-winning-details", "100", timestamp, prizePreview.toString());
        // 记录抽奖日志
        recordLotteryLog(player, guaranteeCommands.toString());
    }

    private void recordLotteryCount(Player player) {
        UUID playerUUID = player.getUniqueId();
        int currentCount = getPlayerLotteryCount(playerUUID);
        setPlayerLotteryCount(playerUUID, currentCount + 1);
    }

    private void recordLastLotteryTime(Player player) {
        UUID playerUUID = player.getUniqueId();
        setPlayerLastLotteryTime(playerUUID, System.currentTimeMillis());
    }

    private void recordLotteryLog(Player player, String prize) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String timestamp = dateFormat.format(new Date());
        String playerName = player.getName();
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO lottery_logs (player_name, result, timestamp) VALUES (?,?,?)")) {
            statement.setString(1, playerName);
            statement.setString(2, prize);
            statement.setString(3, timestamp);
            statement.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("无法记录抽奖日志: " + e.getMessage());
        }
    }

    private void playLotterySound(Player player) {
        String soundName = config.getString("lottery-sound", "BLOCK_NOTE_BLOCK_PLING");
        try {
            Sound sound = Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (IllegalArgumentException e) {
            getLogger().warning("无效的音效名称: " + soundName);
        }
    }

    private void playLotteryParticles(Player player) {
        String particleName = config.getString("lottery-particle", "VILLAGER_HAPPY");
        Location location = player.getLocation();
        try {
            org.bukkit.Particle particle = org.bukkit.Particle.valueOf(particleName);
            player.getWorld().spawnParticle(particle, location, 20, 0.5, 0.5, 0.5, 0.1);
        } catch (IllegalArgumentException e) {
            getLogger().warning("无效的粒子名称: " + particleName);
        }
    }

    private void simulateScreenShake(Player player) {
        if (config.getBoolean("lottery-screen-shake", false)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.CONFUSION, 20, 2));
            Vector direction = player.getLocation().getDirection();
            player.setVelocity(direction.multiply(0.1).setY(0.1));
        }
    }

    private void showWinningDetails(Player player, Map<?, ?> winningPrize) {
        double probability = Double.parseDouble(winningPrize.get("probability").toString());
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String timestamp = dateFormat.format(new Date());
        List<String> commands = (List<String>) winningPrize.get("commands");
        StringBuilder prizePreview = new StringBuilder();
        for (String command : commands) {
            if (command.startsWith("give")) {
                String[] parts = command.split(" ");
                if (parts.length >= 3) {
                    String itemName = parts[2];
                    int amount = Integer.parseInt(parts[3]);
                    prizePreview.append(itemName).append(" x ").append(amount).append(", ");
                }
            }
        }
        if (prizePreview.length() > 0) {
            prizePreview.setLength(prizePreview.length() - 2);
        }
        sendMessage(player, "lottery-winning-details", String.valueOf(probability), timestamp, prizePreview.toString());
    }

    private void loadLanguageConfigs() {
        languageConfigs = new HashMap<>();
        File langFolder = new File(getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }
        for (String langCode : config.getStringList("supported-languages")) {
            File langFile = new File(langFolder, langCode + ".yml");
            if (!langFile.exists()) {
                saveResource("lang/" + langCode + ".yml", false);
            }
            languageConfigs.put(langCode, YamlConfiguration.loadConfiguration(langFile));
        }
    }

    private void sendMessage(Player player, String key, String... args) {
        String langCode = player.getLocale().split("_")[0];
        FileConfiguration langConfig = languageConfigs.getOrDefault(langCode, languageConfigs.get("en"));
        String message = langConfig.getString(key);
        if (message != null) {
            for (int i = 0; i < args.length; i++) {
                message = message.replace("{" + i + "}", args[i]);
            }
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }
    }

    private int getMaxLotteryCount(Player player) {
        if (permission != null) {
            String opPermission = config.getString("op-permission", "lottery.op");
            String memberPermission = config.getString("member-permission", "lottery.member");
            if (permission.has(player, opPermission)) {
                return config.getInt("op-max-lottery-count", 10);
            } else if (permission.has(player, memberPermission)) {
                return config.getInt("member-max-lottery-count", 5);
            }
        }
        return config.getInt("max-lottery-count", 3);
    }

    private List<Map<?, ?>> getPrizesForPlayer(Player player) {
        if (permission != null) {
            String opPermission = config.getString("op-permission", "lottery.op");
            String memberPermission = config.getString("member-permission", "lottery.member");
            if (permission.has(player, opPermission)) {
                return config.getMapList("op-prizes");
            } else if (permission.has(player, memberPermission)) {
                return config.getMapList("member-prizes");
            }
        }
        return config.getMapList("prizes");
    }

    private int getPlayerLotteryCount(UUID playerUUID) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT lottery_count FROM player_lottery_data WHERE player_uuid = ?")) {
            statement.setString(1, playerUUID.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt("lottery_count");
                }
            }
        } catch (SQLException e) {
            getLogger().severe("无法获取玩家抽奖次数: " + e.getMessage());
        }
        return 0;
    }

    private void setPlayerLotteryCount(UUID playerUUID, int count) {
        try (PreparedStatement checkStatement = connection.prepareStatement("SELECT * FROM player_lottery_data WHERE player_uuid = ?")) {
            checkStatement.setString(1, playerUUID.toString());
            try (ResultSet resultSet = checkStatement.executeQuery()) {
                if (resultSet.next()) {
                    try (PreparedStatement updateStatement = connection.prepareStatement("UPDATE player_lottery_data SET lottery_count = ? WHERE player_uuid = ?")) {
                        updateStatement.setInt(1, count);
                        updateStatement.setString(2, playerUUID.toString());
                        updateStatement.executeUpdate();
                    }
                } else {
                    try (PreparedStatement insertStatement = connection.prepareStatement("INSERT INTO player_lottery_data (player_uuid, lottery_count, last_lottery_time, is_guarantee) VALUES (?,?,?,?)")) {
                        insertStatement.setString(1, playerUUID.toString());
                        insertStatement.setInt(2, count);
                        insertStatement.setLong(3, 0);
                        insertStatement.setBoolean(4, false);
                        insertStatement.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            getLogger().severe("无法设置玩家抽奖次数: " + e.getMessage());
        }
    }

    private long getPlayerLastLotteryTime(UUID playerUUID) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT last_lottery_time FROM player_lottery_data WHERE player_uuid = ?")) {
            statement.setString(1, playerUUID.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getLong("last_lottery_time");
                }
            }
        } catch (SQLException e) {
            getLogger().severe("无法获取玩家上次抽奖时间: " + e.getMessage());
        }
        return 0;
    }

    private void setPlayerLastLotteryTime(UUID playerUUID, long time) {
        try (PreparedStatement checkStatement = connection.prepareStatement("SELECT * FROM player_lottery_data WHERE player_uuid = ?")) {
            checkStatement.setString(1, playerUUID.toString());
            try (ResultSet resultSet = checkStatement.executeQuery()) {
                if (resultSet.next()) {
                    try (PreparedStatement updateStatement = connection.prepareStatement("UPDATE player_lottery_data SET last_lottery_time = ? WHERE player_uuid = ?")) {
                        updateStatement.setLong(1, time);
                        updateStatement.setString(2, playerUUID.toString());
                        updateStatement.executeUpdate();
                    }
                } else {
                    try (PreparedStatement insertStatement = connection.prepareStatement("INSERT INTO player_lottery_data (player_uuid, lottery_count, last_lottery_time, is_guarantee) VALUES (?,?,?,?)")) {
                        insertStatement.setString(1, playerUUID.toString());
                        insertStatement.setInt(2, 0);
                        insertStatement.setLong(3, time);
                        insertStatement.setBoolean(4, false);
                        insertStatement.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            getLogger().severe("无法设置玩家上次抽奖时间: " + e.getMessage());
        }
    }

    private boolean isPlayerInGuarantee(UUID playerUUID) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT is_guarantee FROM player_lottery_data WHERE player_uuid = ?")) {
            statement.setString(1, playerUUID.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getBoolean("is_guarantee");
                }
            }
        } catch (SQLException e) {
            getLogger().severe("无法获取玩家保底状态: " + e.getMessage());
        }
        return false;
    }

    private void setPlayerGuaranteeStatus(UUID playerUUID, boolean status) {
        try (PreparedStatement checkStatement = connection.prepareStatement("SELECT * FROM player_lottery_data WHERE player_uuid = ?")) {
            checkStatement.setString(1, playerUUID.toString());
            try (ResultSet resultSet = checkStatement.executeQuery()) {
                if (resultSet.next()) {
                    try (PreparedStatement updateStatement = connection.prepareStatement("UPDATE player_lottery_data SET is_guarantee = ? WHERE player_uuid = ?")) {
                        updateStatement.setBoolean(1, status);
                        updateStatement.setString(2, playerUUID.toString());
                        updateStatement.executeUpdate();
                    }
                } else {
                    try (PreparedStatement insertStatement = connection.prepareStatement("INSERT INTO player_lottery_data (player_uuid, lottery_count, last_lottery_time, is_guarantee) VALUES (?,?,?,?)")) {
                        insertStatement.setString(1, playerUUID.toString());
                        insertStatement.setInt(2, 0);
                        insertStatement.setLong(3, 0);
                        insertStatement.setBoolean(4, status);
                        insertStatement.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            getLogger().severe("无法设置玩家保底状态: " + e.getMessage());
        }
    }

    private void initDatabase() {
        storageType = config.getString("storage-type", "sqlite");
        try {
            if (storageType.equalsIgnoreCase("sqlite")) {
                File databaseFile = new File(getDataFolder(), "lottery.db");
                connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            } else if (storageType.equalsIgnoreCase("mysql")) {
                String host = config.getString("mysql.host", "localhost");
                String port = config.getString("mysql.port", "3306");
                String database = config.getString("mysql.database", "lottery");
                String username = config.getString("mysql.username", "root");
                String password = config.getString("mysql.password", "password");
                connection = DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/" + database, username, password);
            }
        } catch (SQLException e) {
            getLogger().severe("无法连接到数据库: " + e.getMessage());
        }
    }

    private void createTables() {
        try (Statement statement = connection.createStatement()) {
            if (storageType.equalsIgnoreCase("sqlite")) {
                statement.execute("CREATE TABLE IF NOT EXISTS player_lottery_data (" +
                        "player_uuid TEXT PRIMARY KEY, " +
                        "lottery_count INTEGER, " +
                        "last_lottery_time INTEGER, " +
                        "is_guarantee BOOLEAN" +
                        ")");
                statement.execute("CREATE TABLE IF NOT EXISTS lottery_logs (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "player_name TEXT, " +
                        "result TEXT, " +
                        "timestamp TEXT" +
                        ")");
            } else if (storageType.equalsIgnoreCase("mysql")) {
                statement.execute("CREATE TABLE IF NOT EXISTS player_lottery_data (" +
                        "player_uuid VARCHAR(36) PRIMARY KEY, " +
                        "lottery_count INT, " +
                        "last_lottery_time BIGINT, " +
                        "is_guarantee BOOLEAN" +
                        ")");
                statement.execute("CREATE TABLE IF NOT EXISTS lottery_logs (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "player_name VARCHAR(255), " +
                        "result TEXT, " +
                        "timestamp VARCHAR(255)" +
                        ")");
            }
        } catch (SQLException e) {
            getLogger().severe("无法创建数据库表: " + e.getMessage());
        }
    }

    private void closeDatabaseConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            getLogger().severe("无法关闭数据库连接: " + e.getMessage());
        }
    }

    public Map<UUID, Integer> getPlayerLotteryCount() {
        Map<UUID, Integer> countMap = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT player_uuid, lottery_count FROM player_lottery_data")) {
            while (resultSet.next()) {
                UUID playerUUID = UUID.fromString(resultSet.getString("player_uuid"));
                int count = resultSet.getInt("lottery_count");
                countMap.put(playerUUID, count);
            }
        } catch (SQLException e) {
            getLogger().severe("无法获取玩家抽奖次数: " + e.getMessage());
        }
        return countMap;
    }
}    
