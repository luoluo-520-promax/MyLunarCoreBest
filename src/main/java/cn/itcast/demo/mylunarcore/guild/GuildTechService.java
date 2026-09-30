package cn.itcast.demo.mylunarcore.guild;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 公会科技/等级全局增益服务。
 * <p>
 * 业务含义：公会等级（guild.level）不仅是荣誉象征，还会通过科技配置为全体成员提供
 * 全局战斗属性加成——攻击/防御/生命百分比增益与体力恢复加成。
 * 配置来自 data/GuildTechConfigs.json（按公会等级分段配置），
 * 查询时取「≤ 当前公会等级」的最高档配置，保证低等级默认有兜底、高等级自动吃到完整增益。
 * 该增益在玩家出战时由战斗侧（BattleAssistPolicy / 属性计算）按玩家当前公会读取并应用。
 */
@Service
public class GuildTechService {

    private static final Logger log = LoggerFactory.getLogger(GuildTechService.class);

    /**
     * 公会科技增益结算值：均为百分比加成（0.05 = 5%）。
     *
     * @param atkBonusPct          攻击力加成百分比
     * @param defBonusPct          防御力加成百分比
     * @param hpBonusPct           生命值加成百分比
     * @param staminaRegenBonusPct 体力恢复速度加成百分比
     */
    public record GuildBuff(double atkBonusPct, double defBonusPct, double hpBonusPct, double staminaRegenBonusPct) {
        /** 无加成占位值：未入会、无配置或公会等级过低时返回，避免战斗侧空指针。 */
        public static final GuildBuff NONE = new GuildBuff(0, 0, 0, 0);
    }

    /** 单档公会科技配置：guildLevel 达到该等级即启用对应百分比加成。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LevelCfg(int guildLevel, double atkBonusPct, double defBonusPct,
                           double hpBonusPct, double staminaRegenBonusPct) {}

    /** 配置根结构：GuildTechConfigs.json 顶层为 levels 数组。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RootCfg(List<LevelCfg> levels) {}

    /** 公会业务服务：用于反查玩家所在公会的等级。 */
    private final GuildService guildService;
    /** Jackson 解析器，用于读取公会科技 JSON 配置。 */
    private final ObjectMapper objectMapper;
    /** 数据配置目录（默认 data），公会科技配置文件所在目录。 */
    private final Path dataDir;
    /** 已加载并升序排列的等级配置，CopyOnWriteArrayList 保证热更新遍历安全。 */
    private final List<LevelCfg> levels = new CopyOnWriteArrayList<>();

    public GuildTechService(GuildService guildService,
                            ObjectMapper objectMapper,
                            @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.guildService = guildService;
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
    }

    /** Bean 初始化钩子：启动时加载公会科技配置；配置缺失仅告警不影响服务启动。 */
    @PostConstruct
    public void loadConfig() {
        Path file = dataDir.resolve("GuildTechConfigs.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            RootCfg root = objectMapper.readValue(Files.readString(file), RootCfg.class);
            levels.clear();
            if (root != null && root.levels() != null) {
                levels.addAll(root.levels());
                levels.sort(Comparator.comparingInt(LevelCfg::guildLevel));
            }
        } catch (Exception e) {
            log.warn("load GuildTechConfigs failed: {}", e.toString());
        }
    }

    /**
     * 查询某玩家当前可享受的公会科技增益。
     * <p>链路：玩家 ID → 所属公会概要 → 公会等级 → 该等级对应最高档增益。
     * 未入会或玩家 ID 非法时返回 {@link GuildBuff#NONE}（无加成），保证对外口径一致。
     */
    public GuildBuff buffForPlayer(int playerId) {
        if (playerId <= 0) {
            return GuildBuff.NONE;
        }
        GuildService.GuildInfo guild = guildService.loadGuildForPlayer(playerId);
        if (guild == null) {
            return GuildBuff.NONE;
        }
        return buffForGuildLevel(guild.level());
    }

    /**
     * 按公会等级查询对应增益档位。
     * <p>规则：遍历已排序配置，取最后一个 guildLevel ≤ 目标等级 的配置（即不超档的最高档）；
     * 没有满足条件的配置（如等级过低）则返回无加成。
     */
    public GuildBuff buffForGuildLevel(int guildLevel) {
        LevelCfg best = null;
        for (LevelCfg cfg : levels) {
            if (cfg.guildLevel() <= guildLevel) {
                best = cfg;
            }
        }
        if (best == null) {
            return GuildBuff.NONE;
        }
        return new GuildBuff(best.atkBonusPct(), best.defBonusPct(), best.hpBonusPct(), best.staminaRegenBonusPct());
    }
}
