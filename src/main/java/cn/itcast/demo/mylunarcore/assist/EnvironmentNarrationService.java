package cn.itcast.demo.mylunarcore.assist; // 环境旁白服务所在包

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties; // 读取旁白冷却与总开关
import org.springframework.stereotype.Service; // 注册为 Spring 服务

import java.util.Map; // 导出冷却快照
import java.util.Optional; // 表示是否触发旁白
import java.util.concurrent.ConcurrentHashMap; // 保存触发冷却时间

/**
 * 玩家靠近 POI 时按距离触发环境旁白（persona + lore + 可选解谜提示）。
 * <p>
 * 冷却键为 {@code uid|poiId}，冷却秒数来自 {@code AiAssist.environmentNarrationCooldownSeconds}（至少 5 秒）。
 */
@Service
public class EnvironmentNarrationService { // 环境旁白服务
    /**
     * 读取 POI 热更内容
     */
    private final AssistFeatureContentRepository contentRepository;
    /**
     * 读取旁白开关与冷却
     */
    private final LunarCoreProperties properties;
    /**
     * 记录每个玩家对每个 POI 的上次触发时间
     */
    private final ConcurrentHashMap<String, Long> lastPushAt = new ConcurrentHashMap<>();
    /**
     * 构造注入依赖
     */
    public EnvironmentNarrationService(AssistFeatureContentRepository contentRepository, LunarCoreProperties properties) {
        this.contentRepository = contentRepository; // 保存 POI 配置来源
        this.properties = properties; // 保存运行时配置
    } // 构造结束
    /**
     * 环境旁白结果
     */
    public record Narration(String poiId, String title, String message, String source) { // 环境旁白结果
    } // Narration 结束
    /**
     * 尝试触发旁白
     */
    public Optional<Narration> maybeNarrate(long uid, int sceneId, float x, float y, float z) {
        if (uid <= 0 || !properties.getAiAssist().isEnabled()) { // 未登录或助手总开关关闭时不推送
            return Optional.empty(); // 返回空结果
        } // 开关判断结束
        AssistFeatureContent.PoiLore hit = null; // 记录最近的命中 POI
        double bestDist = Double.MAX_VALUE; // 记录当前最短距离
        for (AssistFeatureContent.PoiLore poi : contentRepository.current().pois()) { // 遍历所有 POI 配置
            if (poi == null || (poi.sceneId() > 0 && poi.sceneId() != sceneId)) { // 跳过空项和场景不匹配项
                continue; // 继续扫描其他 POI
            } // 场景过滤结束
            double dx = x - poi.centerX(); // 计算 X 轴距离
            double dy = y - poi.centerY(); // 计算 Y 轴距离
            double dz = z - poi.centerZ(); // 计算 Z 轴距离
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz); // 计算三维欧氏距离
            if (dist <= poi.radius() && dist < bestDist) { // 在触发范围内且更近时更新命中
                bestDist = dist; // 保存更近的距离
                hit = poi; // 保存当前命中的 POI
            } // 距离比较结束
        } // POI 遍历结束
        if (hit == null) { // 周围没有可触发 POI
            return Optional.empty(); // 返回空结果
        } // 命中判断结束
        String key = uid + "|" + hit.poiId(); // 生成玩家与 POI 的冷却键
        long now = System.currentTimeMillis(); // 读取当前时间戳
        long cooldownMs = Math.max(5, properties.getAiAssist().getEnvironmentNarrationCooldownSeconds()) * 1000L; // 冷却至少 5 秒
        Long prev = lastPushAt.get(key); // 读取上次触发时间
        if (prev != null && now - prev < cooldownMs) { // 仍处于冷却期时不重复推送
            return Optional.empty(); // 返回空结果
        } // 冷却判断结束
        lastPushAt.put(key, now); // 记录本次触发时间
        String persona = hit.persona() == null || hit.persona().isBlank() ? "旅人向导" : hit.persona(); // persona 缺省回退为旅人向导
        StringBuilder sb = new StringBuilder(); // 准备拼接旁白文本
        sb.append("【").append(persona).append("】"); // 写入 persona 前缀
        sb.append(nullToEmpty(hit.title())).append('：').append(nullToEmpty(hit.loreShort())); // 拼接标题与简短解说
        if (hit.puzzleHint() != null && !hit.puzzleHint().isBlank()) { // 配置了解谜提示时追加
            sb.append(' ').append("解谜提示：").append(hit.puzzleHint()); // 将解谜提示附加到旁白
        } // 解谜提示判断结束
        return Optional.of(new Narration(hit.poiId(), nullToEmpty(hit.title()), sb.toString(), "poi-rule")); // 返回规则旁白结果
    } // maybeNarrate 结束
    /**
     * 清空所有旁白冷却
     */
    public void clearCooldowns() {
        lastPushAt.clear(); // 允许测试或热更后重新触发
    } // clearCooldowns 结束
    /**
     * 导出冷却表快照
     */
    public Map<String, Long> cooldownSnapshot() {
        return Map.copyOf(lastPushAt); // 返回只读视图
    } // cooldownSnapshot 结束
    /**
     * 空值转空串
     */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s; // 避免文本中出现 null
    } // nullToEmpty 结束
}
