import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;

// PlaceholderAPI 扩展类
public class PlaceholderHook extends PlaceholderExpansion {

    // 插件对象
    private JavaPlugin plugin;
    // 玩家抽奖次数映射
    private Map<UUID, Integer> playerLotteryCount;

    public PlaceholderHook(JavaPlugin plugin) {
        this.plugin = plugin;
        // 从插件主类中获取玩家抽奖次数映射
        this.playerLotteryCount = ((LotteryPlugin) plugin).getPlayerLotteryCount();
    }

    @Override
    public String getIdentifier() {
        // 返回占位符标识符
        return "lottery";
    }

    @Override
    public String getAuthor() {
        // 返回插件作者信息
        return plugin.getDescription().getAuthors().toString();
    }

    @Override
    public String getVersion() {
        // 返回插件版本信息
        return plugin.getDescription().getVersion();
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (params.equalsIgnoreCase("lottery_count")) {
            // 返回玩家的抽奖次数
            return String.valueOf(playerLotteryCount.getOrDefault(player.getUniqueId(), 0));
        }
        return null;
    }
}    
