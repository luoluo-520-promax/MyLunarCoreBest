package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.common.ActivityConfigService;
import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.economy.ShopConfigRepository;
import cn.itcast.demo.mylunarcore.gacha.GachaConfigService;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.quest.QuestConfigRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本地 RAG 知识检索服务。
 * <p>
 * 从活动、任务、卡池、商店、角色使用率与世界观等配置构建短文本知识块，
 * 再按问题与场景做关键词/TF-IDF 打分召回，为 AI 助手提供可引用的事实依据。
 */
@Service
public class RagKnowledgeService {
    /**
     * RAG 业务日志。
     */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, RagKnowledgeService.class);
    /**
     * 同义词归一：把口语/别称折叠到统一知识类型。
     */
    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("主线", "任务"), // 同义词映射：查询词归一到规范词以提升召回
            Map.entry("支线", "任务"), // 同义词映射：查询词归一到规范词以提升召回
            Map.entry("卡池", "抽卡"), // 同义词映射：查询词归一到规范词以提升召回
            Map.entry("扭蛋", "抽卡"), // 同义词映射：查询词归一到规范词以提升召回
            Map.entry("保底", "抽卡"), // 同义词映射：查询词归一到规范词以提升召回
            Map.entry("商店", "shop"), // 同义词映射：查询词归一到规范词以提升召回
            Map.entry("商城", "shop"), // 同义词映射：查询词归一到规范词以提升召回
            Map.entry("热门", "使用率"), // 同义词映射：查询词归一到规范词以提升召回
            Map.entry("出场", "使用率") // 同义词映射：查询词归一到规范词以提升召回
    ); // 结束多行构造或方法调用
    /**
     * activityConfigService：本类持有的 ActivityConfigService 状态/依赖
     */
    private final ActivityConfigService activityConfigService;
    /**
     * questConfigRepository：任务静态配置（标题等）
     */
    private final QuestConfigRepository questConfigRepository;
    /**
     * gachaConfigService：本类持有的 GachaConfigService 状态/依赖
     */
    private final GachaConfigService gachaConfigService;
    /**
     * shopConfigRepository：本类持有的 ShopConfigRepository 状态/依赖
     */
    private final ShopConfigRepository shopConfigRepository;
    /**
     * avatarUsageStatsService：全服角色出场/胜率统计
     */
    private final AvatarUsageStatsService avatarUsageStatsService;
    /**
     * properties：AiAssist/全局配置入口
     */
    private final LunarCoreProperties properties;
    /**
     * worldLoreService：世界观百科
     */
    private final WorldLoreService worldLoreService;
    /**
     * chunks 字段
     */
    private final CopyOnWriteArrayList<KnowledgeChunk> chunks = new CopyOnWriteArrayList<>();
    /**
     * 创建文档频率统计项
     */
    private volatile Map<String, Integer> docFreq = Map.of();
    /**
     * corpusSize：本类持有的 int 状态/依赖
     */
    private volatile int corpusSize;
    /**
     * 构造并注入依赖
     */
    public RagKnowledgeService(ActivityConfigService activityConfigService,
                               QuestConfigRepository questConfigRepository,
                               GachaConfigService gachaConfigService,
                               ShopConfigRepository shopConfigRepository,
                               AvatarUsageStatsService avatarUsageStatsService,
                               LunarCoreProperties properties,
                               WorldLoreService worldLoreService) {
        // 持有活动配置服务，用于把当前活动转成检索知识块。
        this.activityConfigService = activityConfigService;
        // 持有任务配置仓库，用于从任务表抽取标题、目标和奖励摘要。
        this.questConfigRepository = questConfigRepository;
        // 持有卡池配置服务，用于生成抽卡相关知识块和保底说明。
        this.gachaConfigService = gachaConfigService;
        // 持有商店配置仓库，用于把商品和货币信息整理成商店知识块。
        this.shopConfigRepository = shopConfigRepository;
        // 持有角色使用率统计，用于 meta 场景下的热门角色检索。
        this.avatarUsageStatsService = avatarUsageStatsService;
        // 持有总配置，主要用于读取 RAG 的最低相似度阈值。
        this.properties = properties;
        // 持有世界观知识服务，用于补充 lore 类型的固定知识块。
        this.worldLoreService = worldLoreService;
    }
    /**
     * 服务启动后初始化
     */
    @PostConstruct
    public void init() {
        // 容器启动后立即构建一份初始检索语料快照。
        rebuild();
    }
    /**
     * 重建检索语料。 <ol> <li>从活动、任务、卡池、商店、使用率和世界观服务提取结构化数据。</li> <li>把每类数据压缩成短文本知识块并建立文档频次表。</li> <li>整体替换内存快照，保证搜索读取过程无锁且一致。</li> </ol>
     */
    public synchronized void rebuild() {
        // next 保存新的知识块快照，最后一次性替换到共享内存中。
        List<KnowledgeChunk> next = new ArrayList<>();

        // 活动配置按 id 遍历，生成活动知识块。
        for (Map.Entry<Integer, ActivityConfig> e : activityConfigService.snapshot().entrySet()) {
            // 当前活动记录可能为空，空值不参与检索语料。
            ActivityConfig c = e.getValue();
            // 空活动直接跳过，避免构造无意义的知识块。
            if (c == null) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 活动知识块压缩名称、类型、说明、玩法和规则，并带上起止时间。
            String text = truncateChunk(String.join(" ",
                    "活动",
                    nullToEmpty(c.getName()), // 续写 rebuild 的参数列表
                    nullToEmpty(c.getActivityType()), // 续写 rebuild 的参数列表
                    nullToEmpty(c.getDescription()), // 续写 rebuild 的参数列表
                    nullToEmpty(c.getGameplay()), // 续写 rebuild 的参数列表
                    nullToEmpty(c.getRules()), // 续写 rebuild 的参数列表
                    "开始=" + c.getBeginTime(),
                    "结束=" + c.getEndTime())); // 在 rebuild 中调用："结束=" + c.getEndTime()));
            // 使用 activity:activityId 作为稳定主键。
            next.add(new KnowledgeChunk("activity:" + c.getActivityId(), "activity", text,
                    parseEpochMs(c.getBeginTime())));
        }

        // 任务配置遍历后生成任务知识块。
        for (QuestConfigRepository.QuestConfig q : questConfigRepository.listAll()) {
            // 空任务配置直接忽略。
            if (q == null) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // rewardSummary 汇总任务奖励，便于问答引用。
            StringBuilder rewardSummary = new StringBuilder();
            // 奖励列表为空时保持空摘要。
            if (q.rewards() != null) {
                // 遍历每条奖励记录，拼接道具和货币信息。
                for (QuestConfigRepository.RewardConfig r : q.rewards()) {
                    // 空奖励记录不参与摘要。
                    if (r == null) {
                        continue; // 本条数据无效或不匹配，跳到下一项
                    }
                    // itemId 非空时写入道具奖励。
                    if (r.itemId() != null) {
                        rewardSummary.append("道具").append(r.itemId()).append('x').append(r.count() == null ? 1 : r.count()).append(' '); // 在 rebuild 中调用：rewardSummary.append("道具").append(r.itemId()).append('x').append(
                    }
                    // currencyId 非空时写入货币奖励。
                    if (r.currencyId() != null) {
                        rewardSummary.append("货币").append(r.currencyId()).append('x').append(r.amount() == null ? 0 : r.amount()).append(' '); // 在 rebuild 中调用：rewardSummary.append("货币").append(r.currencyId()).append('x').app
                    }
                }
            }
            // objectiveSummary 只取第一条目标，作为任务主线提示。
            String objectiveSummary = "";
            // 当任务目标列表不为空时，提取首个目标做压缩摘要。
            if (q.objectives() != null && !q.objectives().isEmpty()) {
                // 目标配置首项通常最能代表该任务的推进类型。
                QuestConfigRepository.ObjectiveConfig first = q.objectives().get(0);
                // 把目标类型、targetId 和 required 拼入摘要。
                objectiveSummary = "目标类型=" + first.targetType() + " targetId=" + first.targetId()
                        + " 需要完成=" + first.required(); // 在 rebuild 中调用：+ " 需要完成=" + first.required();
            }
            // 将标题、描述、目标与奖励拼成可检索短文本。
            String text = truncateChunk(String.join(" ",
                    "任务",
                    nullToEmpty(q.title()), // 续写 rebuild 的参数列表
                    nullToEmpty(q.description()), // 续写 rebuild 的参数列表
                    objectiveSummary, // 续写 rebuild 的参数列表
                    "奖励",
                    rewardSummary.toString())); // 在 rebuild 中调用：rewardSummary.toString()));
            // 使用 quest:questId 作为任务知识块 id。
            next.add(new KnowledgeChunk("quest:" + q.questId(), "quest", text));
        }

        // 卡池快照按类型和 banner 逐条加入语料。
        gachaConfigService.snapshot().forEach((type, banners) -> {
            // banner 列表为空时不生成知识块。
            if (banners == null) {
                return; // 条件不满足，本方法提前结束不写状态
            }
            // 遍历卡池 banner，输出抽卡、UP 和保底说明。
            banners.forEach(b -> {
                // 空 banner 直接跳过。
                if (b == null) {
                    return; // 条件不满足，本方法提前结束不写状态
                }
                // 使用字符串形式保存五星和四星 UP 列表，方便检索匹配。
                String up5 = b.getRateUpItems5() == null ? "" : b.getRateUpItems5().toString();
                // 使用字符串形式保存四星 UP 列表，补充抽卡知识块信息。
                String up4 = b.getRateUpItems4() == null ? "" : b.getRateUpItems4().toString();
                // 每个 banner 都输出含保底说明的检索文本。
                next.add(new KnowledgeChunk(
                        "banner:" + b.getId(),
                        "gacha",
                        truncateChunk("抽卡 卡池 banner id=" + b.getId() // 续写知识块或摘要文本
                                + " type=" + type // 续写知识块或摘要文本
                                + " gachaType=" + nullToEmpty(b.getGachaType()) // 续写知识块或摘要文本
                                + " 开放时间 begin=" + b.getBeginTime() + " end=" + b.getEndTime() // 续写知识块或摘要文本
                                + " 五星UP=" + up5 // 续写知识块或摘要文本
                                + " 四星UP=" + up4 // 续写知识块或摘要文本
                                + " 官方保底说明：概率与保底以游戏内卡池界面为准，助手不承诺必中。") // 续写知识块或摘要文本
                )); // 结束多行构造或方法调用
            }); // 结束 lambda 或流式构建
        }); // 结束 lambda 或流式构建

        // 商店配置遍历后生成商店知识块。
        for (ShopConfigRepository.ShopConfig shop : shopConfigRepository.listAll()) {
            // 空商店配置不进入语料。
            if (shop == null) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // items 用于压缩前 N 个商品信息。
            StringBuilder items = new StringBuilder();
            // 商店商品列表为空时，items 保持空。
            if (shop.items() != null) {
                // n 限制最多收集 12 个商品，避免单条语料过长。
                int n = 0;
                // 遍历商店商品配置，按顺序拼接信息。
                for (ShopConfigRepository.ShopItemConfig item : shop.items()) {
                    // 空商品或者超过截断上限时，直接略过。
                    if (item == null || n >= 12) {
                        continue; // 本条数据无效或不匹配，跳到下一项
                    }
                    // 拼接商品 id、道具、货币、价格和日限。
                    items.append("商品").append(item.shopItemId())
                            .append(" itemId=").append(item.itemId())
                            .append("x").append(item.itemCount())
                            .append(" 货币").append(item.currencyId())
                            .append(" 价格").append(item.price())
                            .append(" 日限").append(item.dailyLimit())
                            .append(" pay=").append(item.payType())
                            .append(" cat=").append(item.productCategory())
                            .append(' ');
                    // 已记录一条商品，计数加一。
                    n++;
                }
            }
            // itemCount 记录原始商品数量，用于回答“这个商店有多少商品”。
            int itemCount = shop.items() == null ? 0 : shop.items().size();
            // 写入商店知识块，包含商店 id 和商品数。
            next.add(new KnowledgeChunk(
                    "shop:" + shop.shopId(),
                    "shop",
                    truncateChunk("商店 shopId=" + shop.shopId() + " 商品数=" + itemCount + " " + items) // 续写知识块或摘要文本
            )); // 结束多行构造或方法调用
        }

        // 使用率统计快照也作为 meta 语料进入检索。
        next.addAll(buildUsageChunks());
        // 世界观服务若存在，则补充 lore 知识块。
        if (worldLoreService != null) {
            next.addAll(worldLoreService.toKnowledgeChunks()); // 在 rebuild 中调用：next.addAll(worldLoreService.toKnowledgeChunks());
        }

        // df 保存本轮语料的文档频次。
        Map<String, Integer> df = new HashMap<>();
        // 统计每个 token 出现在哪些知识块中。
        for (KnowledgeChunk chunk : next) {
            // 对 chunk 文本做 token 化后统计文档频次。
            for (String token : tokenize(chunk.text())) {
                // 通过 merge 累加 token 的文档计数。
                df.merge(token, 1, Integer::sum);
            }
        }

        // 一次性替换 chunks，保证搜索读到的始终是一致快照。
        chunks.clear();
        // 把新快照写回共享列表。
        chunks.addAll(next);
        // 文档频次表做不可变复制，避免并发写入。
        docFreq = Map.copyOf(df);
        // corpusSize 记录当前语料条数，用于检索中的 idf 计算。
        corpusSize = next.size();
        // 输出重建完成日志，方便排查热更刷新是否成功。
        log.info("RAG knowledge rebuilt, chunkCount={}", chunks.size());
    }

    /**
     * 增量刷新：按 id  upsert/删除指定知识块后重算 df；失败时可回退 {@link #rebuild()}。
     */
    public synchronized void upsertChunks(List<KnowledgeChunk> upserts, Set<String> removeIds) {
        Map<String, KnowledgeChunk> byId = new HashMap<>();
        for (KnowledgeChunk c : chunks) {
            if (c != null && c.id() != null) {
                byId.put(c.id(), c);
            }
        }
        if (removeIds != null) {
            for (String id : removeIds) {
                if (id != null) {
                    byId.remove(id);
                }
            }
        }
        if (upserts != null) {
            for (KnowledgeChunk c : upserts) {
                if (c == null || c.id() == null || c.id().isBlank()) {
                    continue;
                }
                byId.put(c.id(), c);
            }
        }
        List<KnowledgeChunk> next = new ArrayList<>(byId.values());
        Map<String, Integer> df = new HashMap<>();
        for (KnowledgeChunk chunk : next) {
            for (String token : tokenize(chunk.text())) {
                df.merge(token, 1, Integer::sum);
            }
        }
        chunks.clear();
        chunks.addAll(next);
        docFreq = Map.copyOf(df);
        corpusSize = next.size();
        log.info("RAG knowledge incremental upsert done, chunkCount={}", chunks.size());
    }

    /**
     * 热更入口：配置开启增量时先尝试基于当前快照差分刷新活动类知识，否则全量 rebuild。
     */
    public synchronized void reloadPreferIncremental() {
        if (!properties.getAiAssist().isRagIncrementalReload()) {
            rebuild();
            return;
        }
        try {
            List<KnowledgeChunk> upserts = new ArrayList<>();
            Set<String> removeIds = new HashSet<>();
            Set<String> keepActivity = new HashSet<>();
            for (Map.Entry<Integer, ActivityConfig> e : activityConfigService.snapshot().entrySet()) {
                ActivityConfig c = e.getValue();
                if (c == null) {
                    continue;
                }
                String id = "activity:" + c.getActivityId();
                keepActivity.add(id);
                String text = truncateChunk(String.join(" ",
                        "活动",
                        nullToEmpty(c.getName()),
                        nullToEmpty(c.getActivityType()),
                        nullToEmpty(c.getDescription()),
                        nullToEmpty(c.getGameplay()),
                        nullToEmpty(c.getRules()),
                        "开始=" + c.getBeginTime(),
                        "结束=" + c.getEndTime()));
                upserts.add(new KnowledgeChunk(id, "activity", text, parseEpochMs(c.getBeginTime())));
            }
            for (KnowledgeChunk existing : chunks) {
                if (existing.id() != null && existing.id().startsWith("activity:") && !keepActivity.contains(existing.id())) {
                    removeIds.add(existing.id());
                }
            }
            // 版本活动/新角色关联：刷新使用率 meta 块
            for (KnowledgeChunk u : buildUsageChunks()) {
                upserts.add(u);
            }
            upsertChunks(upserts, removeIds);
        } catch (Exception e) {
            log.warn("RAG incremental reload failed, fallback full rebuild: {}", e.toString());
            rebuild();
        }
    }

    /** 热更前快照：chunks + docFreq + corpusSize。 */
    public synchronized Snapshot snapshot() {
        return new Snapshot(List.copyOf(chunks), Map.copyOf(docFreq), corpusSize);
    }

    /** 热更失败时恢复 RAG 内存语料。 */
    public synchronized void restore(Snapshot previous) {
        if (previous == null) {
            return;
        }
        chunks.clear();
        chunks.addAll(previous.chunks());
        docFreq = Map.copyOf(previous.docFreq());
        corpusSize = previous.corpusSize();
        log.info("RAG knowledge restored, chunkCount={}", chunks.size());
    }

    public record Snapshot(List<KnowledgeChunk> chunks, Map<String, Integer> docFreq, int corpusSize) {
    }
    /**
     * 构建结果
     */
    private List<KnowledgeChunk> buildUsageChunks() {
        // 没有使用率统计服务时，meta 语料直接为空。
        if (avatarUsageStatsService == null) {
            return List.of(); // 无命中结果，向上层返回空以便继续降级
        }
        // meta 保存热门角色使用率相关知识块。
        List<KnowledgeChunk> meta = new ArrayList<>();
        // 生成使用率汇总文本，用于回答“版本热门角色”问题。
        String summary = avatarUsageStatsService.summaryText(10);
        // 只有非空摘要才加入语料。
        if (summary != null && !summary.isBlank()) {
            // usage_summary 是一个总览型知识块。
            meta.add(new KnowledgeChunk("meta:usage_summary", "meta",
                    "全服角色使用率热门出场 " + summary)); // 汇总型知识块正文
        }
        // 前 10 名角色逐条转成知识块，用于热门角色检索。
        for (AvatarUsageStatsService.UsageEntry e : avatarUsageStatsService.topN(10)) {
            // 每条热门角色记录都包含排名、出场次数、使用率和胜率。
            meta.add(new KnowledgeChunk(
                    "meta:avatar:" + e.avatarId(),
                    "meta",
                    "角色使用率热门 avatarId=" + e.avatarId() // 单角色热门知识块起始文案
                            + " 排名=#" + e.rank() // 续写知识块或摘要文本
                            + " 出场次数=" + e.pickCount() // 续写知识块或摘要文本
                            + " 使用率=" + Math.round(e.usageRate() * 1000.0) / 10.0 + "%" // 续写知识块或摘要文本
                            + " 胜率=" + Math.round(e.winRate() * 1000.0) / 10.0 + "%" // 续写知识块或摘要文本
                            + " 其他玩家常用" // 续写知识块或摘要文本
            )); // 结束多行构造或方法调用
        }
        // 返回热门角色语料块列表。
        return meta;
    }
    /**
     * 检索匹配项
     */
    public List<KnowledgeChunk> search(String question, int limit) {
        // 调用带 scene 的重载方法，scene 为空时走通用检索。
        return search(question, null, limit);
    }
    /**
     * 检索匹配项
     */
    public List<KnowledgeChunk> search(String question, String scene, int limit) {
        // 空问题、空白问题或非法 limit 直接返回空。
        if (question == null || question.isBlank() || limit <= 0) {
            return List.of(); // 无命中结果，向上层返回空以便继续降级
        }
        // 对问题做同义词扩展和 token 化，以提高召回率。
        Set<String> tokens = expandSynonyms(tokenize(question));
        // 仍然没有 token 时不做检索。
        if (tokens.isEmpty()) {
            return List.of(); // 无命中结果，向上层返回空以便继续降级
        }
        // 场景映射为知识类型，用于给目标类型加权。
        String preferredType = mapSceneToType(scene);
        // 读取 RAG 的最低分阈值，低于该值的候选不返回。
        double minScore = properties.getAiAssist().getRagMinScore();
        // scored 保存评分后的候选，后续按分数排序。
        List<Scored> scored = new ArrayList<>();

        // corpus 是当前快照的可检索语料。
        List<KnowledgeChunk> corpus = new ArrayList<>(chunks);
        // seen 记录当前快照已有的知识块 id，用于动态补充使用率语料。
        Set<String> seen = new HashSet<>();
        // 把快照中的 id 放入 seen，避免后续重复追加。
        for (KnowledgeChunk c : corpus) {
            seen.add(c.id()); // 在 search 中调用：seen.add(c.id());
        }
        // 使用率知识块是动态数据，搜索前补一次最新快照。
        for (KnowledgeChunk live : buildUsageChunks()) {
            // 新知识块则直接追加到检索语料。
            if (seen.add(live.id())) {
                corpus.add(live); // 在 search 中调用：corpus.add(live);
            } else { // 条件不成立时的替代分支
                // 已存在的知识块则用最新版本覆盖旧条目。
                for (int i = 0; i < corpus.size(); i++) {
                    if (live.id().equals(corpus.get(i).id())) { // 若满足 live.id().equals(corpus.get(i).id()) 则走本分支
                        corpus.set(i, live); // 在 search 中调用：corpus.set(i, live);
                        break; // 已达截断/命中条件，结束循环
                    }
                }
            }
        }

        // N 用于 idf 计算，至少为 1。
        int N = Math.max(1, corpusSize);
        // df 读取当前语料的文档频次表。
        Map<String, Integer> df = docFreq;
        // 对每条知识块算分，找与问题最相关的内容。
        for (KnowledgeChunk chunk : corpus) {
            // 把知识块文本 token 化，方便与问题 token 做交集。
            Set<String> docTokens = tokenize(chunk.text());
            // score 累积当前知识块的相关度。
            double score = 0;
            // hay 保存小写文本，便于包含关系判断。
            String hay = chunk.text().toLowerCase(Locale.ROOT);
            // 遍历问题 token，按 tf-idf 风格打分。
            for (String token : tokens) {
                // 文本既不包含 token，token 列表也不包含 token 时直接略过。
                if (!hay.contains(token) && !docTokens.contains(token)) {
                    continue; // 本条数据无效或不匹配，跳到下一项
                }
                // dfVal 取文档频次，至少为 1，避免除零。
                int dfVal = Math.max(1, df.getOrDefault(token, 1));
                // idf 让罕见 token 权重更高。
                double idf = Math.log(1.0 + (N - dfVal + 0.5) / (dfVal + 0.5));
                // tf 统计 token 在知识块中的实际出现次数。
                int tf = 0;
                // 遍历 docTokens，统计与当前 token 完全相等的个数。
                for (String t : docTokens) {
                    if (t.equals(token)) { // 若满足 t.equals(token) 则走本分支
                        tf++; // 累加评分或计数：tf++;
                    }
                }
                // 若 token 被文本包含但 tokenizer 未显式拆出，则给一个保底 tf。
                if (tf == 0 && hay.contains(token)) {
                    tf = 1; // 赋值 tf，供 search 使用
                }
                // tfWeight 用平滑公式降低重复堆词的边际收益。
                double tfWeight = (tf * 2.2) / (tf + 1.2);
                // 按 token 长度决定权重，长 token 权重高于短 token。
                score += idf * tfWeight * (token.length() >= 2 ? 1.0 : 0.5); // 累加评分或计数：score += idf * tfWeight * (token.length() >= 2 ? 1.0 : 0.5);
            }
            // 场景类型命中时额外加分，提升 scene 相关结果排序。
            if (preferredType != null && preferredType.equals(chunk.type())) {
                score += 2.0;
            }
            // 新版本语料时间衰减加权：新鲜窗口内上调（默认 +30%）
            score *= freshnessMultiplier(chunk.publishedAtMs());
            // 达到最低阈值的候选才进入结果集。
            if (score >= minScore) {
                scored.add(new Scored(chunk, score));
            }
        }

        // 按分数从高到低排序，返回最相关的知识块。
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        // 结果条数最多为 limit。
        int n = Math.min(limit, scored.size());
        // result 保存最终返回的知识块列表。
        List<KnowledgeChunk> result = new ArrayList<>(n);
        // 把排序后的前 n 条知识块拷贝出来。
        for (int i = 0; i < n; i++) {
            result.add(scored.get(i).chunk); // 在 search 中调用：result.add(scored.get(i).chunk);
        }
        // 返回检索结果。
        return result;
    }
    /**
     * 返回知识块数量
     */
    public int chunkCount() {
        // 返回当前共享语料快照中的知识块数量。
        return chunks.size();
    }
    /**
     * 按 SYNONYMS 表把查询词扩展为规范词
     */
    static Set<String> expandSynonyms(Set<String> tokens) {
        // out 保存扩展后的 token 集合。
        Set<String> out = new HashSet<>(tokens);
        // 遍历原始 token，按同义词表补充别名。
        for (String t : tokens) {
            // 读取 token 对应的同义词映射。
            String mapped = SYNONYMS.get(t);
            // 有映射时把标准词加入结果集合。
            if (mapped != null) {
                out.add(mapped); // 在 expandSynonyms 中调用：out.add(mapped);
            }
        }
        // 返回同义词扩展后的 token 集合。
        return out;
    }
    /**
     * 把问题拆成中英检索 token 集合
     */
    static Set<String> tokenize(String question) {
        // tokens 保存问题拆分后的检索词。
        Set<String> tokens = new HashSet<>();
        // 统一转小写并去首尾空白，保证 token 稳定。
        String q = question.toLowerCase(Locale.ROOT).trim();
        // 先按空白和常见标点切分出自然词块。
        for (String part : q.split("[\\s,，。！？；:：]+")) {
            // 长度至少 2 的词块才纳入 token 集合。
            if (part.length() >= 2) {
                tokens.add(part); // 在 tokenize 中调用：tokens.add(part);
            }
        }
        // letters 用于把中英文和数字连成连续片段。
        StringBuilder letters = new StringBuilder();
        // 遍历问题中的每个字符，做字符级归一。
        for (int i = 0; i < q.length(); i++) {
            // 取出当前字符，判断是否可拼接成检索词。
            char c = q.charAt(i);
            // 字母、数字和中文字符保留，其余字符转为空格。
            if (Character.isLetterOrDigit(c) || isCjk(c)) {
                letters.append(c); // 在 tokenize 中调用：letters.append(c);
            } else { // 条件不成立时的替代分支
                letters.append(' '); // 在 tokenize 中调用：letters.append(' ');
            }
        }
        // compact 去掉空格后形成连续片段，用于二元滑窗。
        String compact = letters.toString().replace(" ", "");
        // 通过 2 字符滑窗补充 token，提高短文本召回。
        for (int i = 0; i + 1 < compact.length(); i++) {
            tokens.add(compact.substring(i, i + 2)); // 在 tokenize 中调用：tokens.add(compact.substring(i, i + 2));
        }
        // 返回问题 token 集合。
        return tokens;
    }
    /**
     * 判断是否为中日韩字符
     */
    private static boolean isCjk(char c) {
        // 判断字符是否属于常见 CJK 中文区块。
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        // 只要属于主要汉字区块之一，就视为中文字符。
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS // 延续上一行布尔条件
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A; // 延续上一行布尔条件
    }
    /**
     * 将场景映射为知识类型
     */
    private static String mapSceneToType(String scene) {
        // 空 scene 不参与类型映射。
        if (scene == null || scene.isBlank()) {
            return null; // 返回：null
        }
        // 按 scene 名称映射到知识块类型，辅助排序加权。
        return switch (scene.trim().toLowerCase(Locale.ROOT)) {
            case "quest", "growth" -> "quest"; // 匹配分支 case "quest", "growth" -> "quest"; 的场景预算/来源处理
            case "gacha" -> "gacha"; // 匹配分支 case "gacha" -> "gacha"; 的场景预算/来源处理
            case "activity" -> "activity";
            case "announce", "patch", "version", "公告", "更新" -> "announce";
            case "shop" -> "shop";
            case "meta", "usage", "popular", "热门" -> "meta"; // 匹配分支 case "meta", "usage", "popular", "热门" -> "meta"; 的场景预算/来源处理
            case "lore", "wiki", "百科", "世界观" -> "lore"; // 匹配分支 case "lore", "wiki", "百科", "世界观" -> "lore"; 的场景预算/来源处理
            default -> null; // 匹配分支 default -> null; 的场景预算/来源处理
        };
    }
    /**
     * 截断知识块文本
     */
    private static String truncateChunk(String text) {
        // 空文本直接返回空串，避免构造空知识块内容。
        if (text == null) {
            return ""; // 返回：""
        }
        // 去掉首尾空白并压缩连续空格，减少语料噪音。
        String t = text.trim().replaceAll("\\s+", " ");
        // 长度不超过 400 时直接返回原文。
        if (t.length() <= 400) {
            return t; // 返回：t
        }
        // 超长文本只保留前 400 字符，防止知识块过大。
        return t.substring(0, 400);
    }
    /**
     * 公告/更新日志灌库：Markdown/文本切块写入本地 RAG 语料。
     *
     * @return 写入的知识块数量
     */
    public synchronized int ingestAnnouncement(String announcementId, String title, String markdown, long publishedAtMs) {
        if (announcementId == null || announcementId.isBlank()) {
            return 0;
        }
        String body = (title == null ? "" : title + "\n") + (markdown == null ? "" : markdown);
        if (body.isBlank()) {
            return 0;
        }
        long ts = publishedAtMs > 0 ? publishedAtMs : System.currentTimeMillis();
        List<String> parts = chunkMarkdown(body, 360);
        List<KnowledgeChunk> upserts = new ArrayList<>();
        int i = 0;
        for (String part : parts) {
            String id = "announce:" + announcementId.trim() + ":" + (i++);
            upserts.add(new KnowledgeChunk(id, "announce", truncateChunk("公告更新 " + part), ts));
        }
        upsertChunks(upserts, Set.of());
        log.info("RAG announcement ingested id={} chunks={}", announcementId, upserts.size());
        return upserts.size();
    }

    private double freshnessMultiplier(long publishedAtMs) {
        if (publishedAtMs <= 0) {
            return 1.0;
        }
        int days = Math.max(1, properties.getAiAssist().getRagFreshnessDays());
        double boost = Math.max(0.0, properties.getAiAssist().getRagFreshnessBoost());
        long ageMs = Math.max(0L, System.currentTimeMillis() - publishedAtMs);
        long windowMs = days * 86_400_000L;
        if (ageMs <= windowMs) {
            return 1.0 + boost;
        }
        return 1.0;
    }

    private static long parseEpochMs(long maybeEpochSecondsOrMs) {
        if (maybeEpochSecondsOrMs <= 0) {
            return 0L;
        }
        if (maybeEpochSecondsOrMs < 10_000_000_000L) {
            return maybeEpochSecondsOrMs * 1000L;
        }
        return maybeEpochSecondsOrMs;
    }

    static List<String> chunkMarkdown(String text, int maxLen) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String[] paras = text.replace("\r\n", "\n").split("\n{2,}");
        StringBuilder buf = new StringBuilder();
        for (String p : paras) {
            String part = p.trim();
            if (part.isEmpty()) {
                continue;
            }
            if (buf.length() + part.length() + 1 > maxLen && buf.length() > 0) {
                out.add(buf.toString());
                buf.setLength(0);
            }
            if (part.length() > maxLen) {
                if (buf.length() > 0) {
                    out.add(buf.toString());
                    buf.setLength(0);
                }
                for (int i = 0; i < part.length(); i += maxLen) {
                    out.add(part.substring(i, Math.min(part.length(), i + maxLen)));
                }
            } else {
                if (buf.length() > 0) {
                    buf.append(' ');
                }
                buf.append(part);
            }
        }
        if (buf.length() > 0) {
            out.add(buf.toString());
        }
        return out;
    }

    /**
     * 知识库文本块（publishedAtMs>0 时参与新鲜度加权）
     */
    public record KnowledgeChunk(String id, String type, String text, long publishedAtMs) {
        public KnowledgeChunk(String id, String type, String text) {
            this(id, type, text, 0L);
        }
    }
    /**
     * 带评分的知识块
     */
    private record Scored(KnowledgeChunk chunk, double score) {
    }
    /**
     * 将 null 转为空字符串
     */
    private static String nullToEmpty(String s) {
        // 统一把 null 映射为空串，便于拼接知识文本。
        return s == null ? "" : s;
    }
}
