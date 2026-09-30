package cn.itcast.demo.mylunarcore.skin; // 皮肤静态配置仓储所在包

import cn.itcast.demo.mylunarcore.common.AppLogger; // 统一业务/系统日志入口
import cn.itcast.demo.mylunarcore.common.ConfigFileService; // 读取 data 目录下 JSON 配置
import cn.itcast.demo.mylunarcore.common.LogCategory; // 日志分类枚举
import com.fasterxml.jackson.annotation.JsonIgnoreProperties; // 忽略 JSON 中未知字段，便于向前兼容
import jakarta.annotation.PostConstruct; // 容器启动后自动加载配置
import org.slf4j.Logger; // 记录加载/回滚日志
import org.springframework.stereotype.Repository; // 注册为配置仓储组件

import java.util.ArrayList; // 构建可变索引列表
import java.util.Collections; // 提供不可变空 Map / 不可变包装
import java.util.HashMap; // 构建 skinId / itemId / avatarId 索引
import java.util.HashSet; // 检测 skinId、itemId 是否重复
import java.util.List; // 皮肤配置列表类型
import java.util.Map; // 索引 Map 类型
import java.util.Set; // 去重集合类型

/**
 * 皮肤静态配置仓储：从 data/SkinConfigs.json 加载，支持热更与失败回滚。
 * 加载时强校验 skinId、itemId 唯一，以及 avatarId、itemId 必填绑定。
 */
@Repository // 作为配置数据访问层注册到 Spring
public class SkinConfigRepository {

    /**
     * 系统类日志，记录配置加载成功、失败与回滚。
     */
    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, SkinConfigRepository.class);

    /**
     * 皮肤配置文件名，位于 data 目录下，与热更发布约定一致。
     */
    public static final String CONFIG_FILE = "SkinConfigs.json";

    /**
     * 通用配置文件读写服务，负责反序列化 JSON。
     */
    private final ConfigFileService configFileService;

    /**
     * 当前生效的配置文件快照；volatile 保证热更后其它线程可见。
     */
    private volatile SkinConfigsFile current = SkinConfigsFile.empty();
    /**
     * skinId → 配置 的只读索引，供按皮肤 ID 查询。
     */
    private Map<Integer, SkinConfig> bySkinId = Collections.emptyMap();
    /**
     * itemId → 配置 的只读索引，供按背包道具反查皮肤。
     */
    private Map<Integer, SkinConfig> byItemId = Collections.emptyMap();
    /**
     * avatarId → 该角色全部皮肤列表 的只读索引。
     */
    private Map<Integer, List<SkinConfig>> byAvatarId = Collections.emptyMap();

    /**
     * 构造注入配置文件服务。
     *
     * @param configFileService JSON 配置读写
     */
    public SkinConfigRepository(ConfigFileService configFileService) {
        this.configFileService = configFileService; // 保存读写依赖
    } // 构造结束

    /**
     * Spring 启动完成后自动执行首次加载。
     */
    @PostConstruct // 生命周期回调：Bean 创建后加载
    public void load() {
        reload(); // 委托给可热更的 reload
    } // load 结束

    /**
     * 从磁盘重新加载 SkinConfigs.json 并重建索引；失败时保留旧配置并返回 false。
     *
     * @return true 表示加载并切换成功；false 表示文件空或异常，仍用上一份
     */
    public boolean reload() {
        try { // 捕获读文件与索引构建异常，避免热更打挂服务
            SkinConfigsFile loaded = configFileService.readJson(CONFIG_FILE, SkinConfigsFile.class); // 反序列化配置文件
            if (loaded == null) { // 文件缺失或内容为空对象
                log.warn("SkinConfigs empty, keep previous"); // 告警并保留旧数据
                return false; // 不切换索引
            } // 空文件判断结束
            Indexes indexes = buildIndexes(loaded); // 强校验并构建三项索引
            current = loaded; // 切换当前快照
            bySkinId = indexes.bySkinId; // 发布 skinId 索引
            byItemId = indexes.byItemId; // 发布 itemId 索引
            byAvatarId = indexes.byAvatarId; // 发布按角色分组索引
            log.info("SkinConfigs loaded, schemaVersion={}, skinCount={}",
                    loaded.schemaVersion(), indexes.bySkinId.size()); // 记录版本与条目数
            return true; // 热更成功
        } catch (Exception e) { // 解析失败、唯一性冲突等
            log.warn("SkinConfigs reload failed, keep previous", e); // 打完整异常，保留旧索引
            return false; // 调用方据此决定是否回滚文件
        } // try-catch 结束
    } // reload 结束

    /**
     * 返回当前生效的配置文件快照（含 schemaVersion 与 skins 列表）。
     *
     * @return 当前 SkinConfigsFile
     */
    public SkinConfigsFile current() {
        return current; // 直接返回 volatile 快照引用
    } // current 结束

    /**
     * 将内存配置回滚到指定历史快照（热更失败时由上层传入备份）。
     *
     * @param previous 先前成功的配置快照；null 则忽略
     */
    public void restore(SkinConfigsFile previous) {
        if (previous == null) { // 无备份可回滚
            return; // 静默跳过
        } // null 判断结束
        try { // 回滚时同样需要重建索引并校验
            Indexes indexes = buildIndexes(previous); // 对历史快照重建索引
            current = previous; // 恢复快照
            bySkinId = indexes.bySkinId; // 恢复 skinId 索引
            byItemId = indexes.byItemId; // 恢复 itemId 索引
            byAvatarId = indexes.byAvatarId; // 恢复角色索引
        } catch (Exception e) { // 备份本身也不合法时只打日志
            log.warn("SkinConfigs restore failed", e); // 不抛出，避免连锁故障
        } // try-catch 结束
    } // restore 结束

    /**
     * 按皮肤 ID 查询配置。
     *
     * @param skinId 皮肤 ID
     * @return 配置；不存在返回 null
     */
    public SkinConfig find(int skinId) {
        return bySkinId.get(skinId); // O(1) 索引查找
    } // find 结束

    /**
     * 按道具配置 ID 反查皮肤（发放/拥有判定用）。
     *
     * @param itemId 皮肤道具 ID
     * @return 配置；非皮肤道具返回 null
     */
    public SkinConfig findByItemId(int itemId) {
        return byItemId.get(itemId); // O(1) 反查
    } // findByItemId 结束

    /**
     * 列出某角色绑定的全部皮肤（含未启用），列表不可变。
     *
     * @param avatarId 角色 ID
     * @return 皮肤列表；无则空列表
     */
    public List<SkinConfig> listByAvatarId(int avatarId) {
        return byAvatarId.getOrDefault(avatarId, List.of()); // 缺省返回不可变空列表
    } // listByAvatarId 结束

    /**
     * 列出某角色已启用（enabled）的皮肤，供衣柜展示。
     *
     * @param avatarId 角色 ID
     * @return 启用中的皮肤不可变列表
     */
    public List<SkinConfig> listEnabledByAvatarId(int avatarId) {
        List<SkinConfig> all = listByAvatarId(avatarId); // 先取该角色全部配置
        if (all.isEmpty()) { // 无配置则直接返回
            return List.of(); // 空不可变列表
        } // 空列表判断结束
        List<SkinConfig> enabled = new ArrayList<>(); // 收集启用项
        for (SkinConfig skin : all) { // 过滤未启用
            if (skin != null && skin.isEnabled()) { // 非空且 enabled 为真（含 null 默认启用）
                enabled.add(skin); // 纳入衣柜可见集
            } // 单条过滤结束
        } // 遍历结束
        return List.copyOf(enabled); // 返回不可变副本，防止外部修改索引
    } // listEnabledByAvatarId 结束

    /**
     * 查找某角色当前启用的默认皮肤（isDefault=true）；多条时返回先遍历到的第一条。
     *
     * @param avatarId 角色 ID
     * @return 默认皮肤；无则 null
     */
    public SkinConfig findDefault(int avatarId) {
        for (SkinConfig skin : listByAvatarId(avatarId)) { // 扫描该角色皮肤
            if (skin != null && skin.isEnabled() && skin.isDefault()) { // 启用且标记为默认
                return skin; // 作为创角/还原默认的目标
            } // 条件判断结束
        } // 扫描结束
        return null; // 配置缺失时由调用方写 equippedSkinId=0
    } // findDefault 结束

    /**
     * 返回全部皮肤配置的不可变视图（按 skinId Map 的 values）。
     *
     * @return 全量皮肤列表副本
     */
    public List<SkinConfig> listAll() {
        return List.copyOf(bySkinId.values()); // 拷贝 values，避免暴露内部 Map
    } // listAll 结束

    /**
     * 根据配置文件构建三项索引，并强校验 skinId/itemId 唯一及必填字段。
     *
     * @param file 反序列化后的配置根对象
     * @return 不可变索引集合
     * @throws IllegalStateException 配置非法时抛出，触发 reload 回退
     */
    private static Indexes buildIndexes(SkinConfigsFile file) {
        Map<Integer, SkinConfig> skinMap = new HashMap<>(); // 构建中的 skinId 索引
        Map<Integer, SkinConfig> itemMap = new HashMap<>(); // 构建中的 itemId 索引
        Map<Integer, List<SkinConfig>> avatarMap = new HashMap<>(); // 构建中的按角色分组
        Set<Integer> seenSkin = new HashSet<>(); // 检测重复 skinId
        Set<Integer> seenItem = new HashSet<>(); // 检测重复 itemId
        List<SkinConfig> skins = file.skins() == null ? List.of() : file.skins(); // 空 skins 按空列表处理
        for (SkinConfig skin : skins) { // 逐条校验并入索引
            if (skin == null || skin.skinId() <= 0) { // skinId 必须为正
                throw new IllegalStateException("invalid skinId in SkinConfigs"); // 中断整份加载
            } // skinId 校验结束
            if (skin.avatarId() <= 0) { // 必须绑定角色
                throw new IllegalStateException("skin " + skin.skinId() + " missing avatarId"); // 标明出错条目
            } // avatarId 校验结束
            if (skin.itemId() <= 0) { // 必须绑定道具 ID（默认皮也有占位 itemId）
                throw new IllegalStateException("skin " + skin.skinId() + " missing itemId"); // 标明出错条目
            } // itemId 校验结束
            if (!seenSkin.add(skin.skinId())) { // Set.add 返回 false 表示重复
                throw new IllegalStateException("duplicate skinId=" + skin.skinId()); // 禁止重复主键
            } // skinId 唯一性结束
            if (!seenItem.add(skin.itemId())) { // itemId 全局唯一，避免一道具对应多皮肤
                throw new IllegalStateException("duplicate itemId=" + skin.itemId()
                        + " for skinId=" + skin.skinId()); // 带上冲突双方信息
            } // itemId 唯一性结束
            skinMap.put(skin.skinId(), skin); // 写入 skinId 索引
            itemMap.put(skin.itemId(), skin); // 写入 itemId 索引
            avatarMap.computeIfAbsent(skin.avatarId(), k -> new ArrayList<>()).add(skin); // 按角色追加到列表
        } // 条目循环结束
        Map<Integer, List<SkinConfig>> immutableAvatar = new HashMap<>(); // 将角色下列表冻结为不可变
        for (Map.Entry<Integer, List<SkinConfig>> e : avatarMap.entrySet()) { // 逐角色冻结
            immutableAvatar.put(e.getKey(), List.copyOf(e.getValue())); // List.copyOf 防止外部增删
        } // 冻结循环结束
        return new Indexes(
                Collections.unmodifiableMap(skinMap), // 对外只读 Map
                Collections.unmodifiableMap(itemMap), // 对外只读 Map
                Collections.unmodifiableMap(immutableAvatar)); // 对外只读且 value 也不可变
    } // buildIndexes 结束

    /**
     * 内部索引容器：一次构建后整体替换三个字段，保证热更原子性语义。
     *
     * @param bySkinId   skinId 索引
     * @param byItemId   itemId 索引
     * @param byAvatarId 角色分组索引
     */
    private record Indexes(
            Map<Integer, SkinConfig> bySkinId,
            Map<Integer, SkinConfig> byItemId,
            Map<Integer, List<SkinConfig>> byAvatarId) {
    } // Indexes 结束

    /**
     * SkinConfigs.json 根结构：schema 版本号 + 皮肤条目列表。
     *
     * @param schemaVersion 配置 schema 版本，便于兼容迁移
     * @param skins         皮肤条目；反序列化 null 时规范为空列表
     */
    @JsonIgnoreProperties(ignoreUnknown = true) // 忽略未来新增未知字段，避免热更解析失败
    public record SkinConfigsFile(int schemaVersion, List<SkinConfig> skins) {
        /**
         * 紧凑构造：将 null skins 规范为空列表，避免 NPE。
         */
        public SkinConfigsFile {
            if (skins == null) { // JSON 缺省 skins
                skins = List.of(); // 使用不可变空列表
            } // null 规范化结束
        } // 紧凑构造结束

        /**
         * 启动占位：版本 0、无条目，在首次成功加载前使用。
         *
         * @return 空配置快照
         */
        public static SkinConfigsFile empty() {
            return new SkinConfigsFile(0, List.of()); // 空快照
        } // empty 结束
    } // SkinConfigsFile 结束

    /**
     * 单条皮肤静态配置，字段与 SkinConfigs.json 条目一一对应。
     *
     * @param skinId      皮肤唯一 ID
     * @param avatarId    绑定角色 ID
     * @param itemId      对应背包道具配置 ID
     * @param name        展示名称
     * @param rarity      稀有度
     * @param isDefault   是否默认外观
     * @param resourceKey 客户端资源路径键
     * @param previewIcon 预览图标资源
     * @param tags        标签列表（如 DEFAULT、LIMITED）
     * @param obtainTips  获取途径文案
     * @param enabled     是否启用；JSON 缺省时视为 true
     */
    @JsonIgnoreProperties(ignoreUnknown = true) // 向前兼容未知字段
    public record SkinConfig(
            int skinId,
            int avatarId,
            int itemId,
            String name,
            int rarity,
            boolean isDefault,
            String resourceKey,
            String previewIcon,
            List<String> tags,
            String obtainTips,
            Boolean enabled
    ) {
        /**
         * 紧凑构造：将可空字符串/列表/开关规范为安全默认值，避免调用方判空。
         */
        public SkinConfig {
            if (tags == null) { // 缺 tags
                tags = List.of(); // 空标签列表
            } // tags 规范化结束
            if (enabled == null) { // JSON 未写 enabled
                enabled = Boolean.TRUE; // 默认启用
            } // enabled 规范化结束
            if (name == null) { // 缺名称
                name = ""; // 空串，协议层可直接下发
            } // name 规范化结束
            if (resourceKey == null) { // 缺资源键
                resourceKey = ""; // 空串
            } // resourceKey 规范化结束
            if (previewIcon == null) { // 缺预览图
                previewIcon = ""; // 空串
            } // previewIcon 规范化结束
            if (obtainTips == null) { // 缺获取提示
                obtainTips = ""; // 空串
            } // obtainTips 规范化结束
        } // 紧凑构造结束

        /**
         * 是否启用：enabled 为 null 时按启用处理（兼容旧数据）。
         *
         * @return true 表示衣柜与穿戴逻辑应识别该皮肤
         */
        public boolean isEnabled() {
            return enabled == null || enabled; // null 或 true 均视为启用
        } // isEnabled 结束
    } // SkinConfig 结束
}
