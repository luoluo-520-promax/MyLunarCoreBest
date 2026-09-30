package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.character.AttributeCalculator;
import cn.itcast.demo.mylunarcore.character.TalentApplicationService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.equipment.EquipmentAffixSnapshotService;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.model.AvatarTalentEntity;
import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.model.PlayerEntity;
import cn.itcast.demo.mylunarcore.model.QuestProgressEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AssistContextBuilder {
    /**
     * properties：AiAssist/全局配置入口
     */
    private final LunarCoreProperties properties;
    /**
     * attributeCalculator：角色面板属性换算
     */
    private final AttributeCalculator attributeCalculator;
    /**
     * talentApplicationService：角色天赋列表
     */
    private final TalentApplicationService talentApplicationService;
    /**
     * avatarUsageStatsService：全服角色出场/胜率统计
     */
    private final AvatarUsageStatsService avatarUsageStatsService;
    /**
     * playerProfileService：常用角色/未完成任务等个性化画像
     */
    private final AssistPlayerProfileService playerProfileService;
    private final EquipmentAffixSnapshotService equipmentAffixSnapshotService;

    public AssistContextBuilder(LunarCoreProperties properties,
                                AttributeCalculator attributeCalculator,
                                TalentApplicationService talentApplicationService,
                                AvatarUsageStatsService avatarUsageStatsService,
                                AssistPlayerProfileService playerProfileService,
                                EquipmentAffixSnapshotService equipmentAffixSnapshotService) {
        this.properties = properties;
        this.attributeCalculator = attributeCalculator;
        this.talentApplicationService = talentApplicationService;
        this.avatarUsageStatsService = avatarUsageStatsService;
        this.playerProfileService = playerProfileService;
        this.equipmentAffixSnapshotService = equipmentAffixSnapshotService;
    }
    /**
     * 构建 AI 助手可用的白名单上下文。 <ol> <li>归一化 scene，决定本次上下文预算。</li> <li>投影玩家基础信息、活动任务、背包、角色、天赋与热度摘要。</li> <li>输出不可变 Map，供提示词、RAG 和远程请求复用。</li> </ol>
     */
    public Map<String, Object> build(PlayerData playerData, List<QuestProgressEntity> quests, String scene) {
        // scene 为空时统一折叠到 general，确保缓存键和提示词维度稳定。
        String resolved = scene == null || scene.isBlank() ? "general" : scene.trim().toLowerCase(Locale.ROOT);
        // 使用 LinkedHashMap 保持字段顺序，方便远程请求和调试日志阅读。
        Map<String, Object> ctx = new LinkedHashMap<>();
        // 把归一化后的场景写入上下文，供远程助手和本地 LLM 判断分支。
        ctx.put("scene", resolved);

        // 默认 playerId=0，避免玩家数据缺失时继续向下游传播非法主键。
        int playerId = 0;
        // 只有玩家对象存在时，才提取 uid、等级、体力和场景信息。
        if (playerData != null && playerData.getPlayer() != null) {
            // 取出 PlayerEntity，后续把基础面板投影到上下文里。
            PlayerEntity p = playerData.getPlayer();
            // 只保留低 32 位作为 int playerId，和多数仓库查询口径保持一致。
            playerId = (int) (p.getUid() & 0xffffffffL);
            // 角色等级用于判断成长阶段和任务卡点。
            ctx.put("level", p.getLevel());
            // 世界等级用于判断副本/材料推荐强度。
            ctx.put("worldLevel", p.getWorldLevel());
            // 体力用于判断是否适合刷本或做活动。
            ctx.put("stamina", p.getStamina());
            // 场景 id 用于推断当前所在大地图或界面。
            ctx.put("sceneId", p.getSceneId());
        }

        // 根据场景决定投影预算，避免 general 场景把上下文撑得太大。
        SceneBudget budget = budgetFor(resolved);
        // 任务只保留少量关键进行中/待提交条目，减少噪声。
        List<Map<String, Object>> activeQuests = projectActiveQuests(quests, budget.maxQuests());
        // 任务快照直接写入上下文，供问答、RAG 和远程服务复用。
        ctx.put("activeQuests", activeQuests);

        // 读取 aiAssist 子配置，后续投影限制都从这里取。
        LunarCoreProperties.AiAssistProperties cfg = properties.getAiAssist();
        // 背包投影会记录总数与截断状态，防止上下文过大。
        InventoryProjection inventory = projectInventory(playerData, budget.maxInventory());
        // 不同场景决定是否公开背包明细。
        if (budget.includeInventory()) {
            // inventory.slots() 只返回白名单字段的物品投影。
            ctx.put("inventory", inventory.slots());
            // totalCount 用于回答“我有多少材料/道具”。
            ctx.put("inventoryCount", inventory.totalCount());
            // truncated 告诉上层这是截断后的明细，不是完整背包。
            ctx.put("inventoryTruncated", inventory.truncated());
        } else { // 条件不成立时的替代分支
            // 不公开时只保留空列表，避免暴露过多道具明细。
            ctx.put("inventory", List.of());
            // 计数仍然保留，用于监测和规则提示。
            ctx.put("inventoryCount", inventory.totalCount());
            // 不显示明细时，直接标记为截断。
            ctx.put("inventoryTruncated", true);
        }

        // 角色投影默认空列表，只有预算允许才填充。
        List<Map<String, Object>> avatars = List.of();
        // 只有 budget 允许公开角色时，才投影角色和天赋。
        if (budget.includeAvatars()) {
            // 角色数量受预算限制，天赋是否展示由 includeTalents 决定。
            avatars = projectAvatars(playerData, playerId, budget.maxAvatars(),
                    budget.includeTalents() ? cfg.getContextMaxTalentsPerAvatar() : 0); // 在 build 中调用：budget.includeTalents() ? cfg.getContextMaxTalentsPerAvatar() : 0
            // 将角色投影写入上下文，供阵容、养成、战斗建议使用。
            ctx.put("avatars", avatars);
            // 角色数量用于监测上下文密度。
            ctx.put("avatarCount", avatars.size());
        } else { // 条件不成立时的替代分支
            // 不显示角色明细时保持空列表。
            ctx.put("avatars", List.of());
            // 角色数量归零，避免误导上层认为存在投影数据。
            ctx.put("avatarCount", 0);
        }

        // meta 场景或允许补充 meta 信息时，使用率榜单才会进入上下文。
        int usageTopN = budget.includeMeta() ? Math.max(1, cfg.getAvatarUsageContextTopN()) : 0;
        // 默认使用率榜单为空列表。
        List<Map<String, Object>> globalUsage = List.of();
        // 默认摘要为空串，避免无数据时拼进提示词。
        String usageSummary = "";
        // 仅当需要 meta 信息且统计服务可用时，才读取全服热门角色。
        if (usageTopN > 0 && avatarUsageStatsService != null) {
            // topNAsMaps 提供适合直接塞进 JSON 上下文的结构。
            List<Map<String, Object>> rows = avatarUsageStatsService.topNAsMaps(usageTopN);
            // rows 为 null 时不覆盖默认空列表。
            if (rows != null) {
                // 保存热门角色榜单原样数据，供提示词或远程请求直接使用。
                globalUsage = rows;
            }
            // summaryText 用于生成一段紧凑的中文热门摘要。
            String summary = avatarUsageStatsService.summaryText(Math.min(5, usageTopN));
            // summary 为空时不覆盖默认值。
            if (summary != null) {
                // 热门摘要用于解释“为什么推荐某个角色”。
                usageSummary = summary;
            }
        }
        // 全服热门角色表进入上下文，供 meta 问答和推荐使用。
        ctx.put("globalAvatarUsage", globalUsage);
        // 中文摘要进入上下文，供 prompt 直接引用。
        ctx.put("globalUsageSummary", usageSummary);
        // monitoringSummary 汇总背包、角色和热度信息，方便规则兜底输出。
        ctx.put("monitoringSummary", buildMonitoringSummary(inventory, avatars, usageSummary));

        // 个性化画像：常用角色 / 未完成任务 / 配额档位。
        if (playerProfileService != null) {
            Map<String, Object> profile = playerProfileService.buildProfile(playerData, quests);
            ctx.put("playerProfile", profile);
            Object hint = profile.get("personalizationHint");
            if (hint instanceof String hs && !hs.isBlank()) {
                ctx.put("personalizationHint", hs);
            }
        }
        if (equipmentAffixSnapshotService != null && playerData != null) {
            ctx.put("equipmentSnapshot", equipmentAffixSnapshotService.buildSnapshot(
                    playerData, Math.max(1, cfg.getContextMaxAvatars())));
        }
        ctx.put("abyssWeakTags", List.of("imaginary", "quantum", "ice"));

        // Map.copyOf 让返回结果不可变，防止下游误改上下文快照。
        return Map.copyOf(ctx);
    }
    /**
     * 按场景分配上下文预算
     */
    private SceneBudget budgetFor(String scene) {
        // 读取 aiAssist 配置，按场景预算上下文宽度。
        LunarCoreProperties.AiAssistProperties cfg = properties.getAiAssist();
        // 默认背包投影上限，负值会被折叠为 0。
        int invDefault = Math.max(0, cfg.getContextMaxInventorySlots());
        // 默认角色投影上限，负值会被折叠为 0。
        int avatarDefault = Math.max(0, cfg.getContextMaxAvatars());
        // 不同场景采用不同预算，避免 general 场景过载。
        return switch (scene) {
            case "quest" -> new SceneBudget(true, true, true, false, Math.min(invDefault, 48), Math.min(avatarDefault, 8), 3); // 匹配分支 case "quest" -> new SceneBudget(true, true, true, false, Math.min(invD 的场景预算/来源处理
            case "gacha" -> new SceneBudget(false, false, false, false, 0, 0, 3); // 匹配分支 case "gacha" -> new SceneBudget(false, false, false, false, 0, 0, 3); 的场景预算/来源处理
            case "growth" -> new SceneBudget(true, true, true, false, Math.min(invDefault, 64), avatarDefault, 3); // 匹配分支 case "growth" -> new SceneBudget(true, true, true, false, Math.min(inv 的场景预算/来源处理
            case "activity" -> new SceneBudget(false, false, false, false, 0, 0, 3); // 匹配分支 case "activity" -> new SceneBudget(false, false, false, false, 0, 0, 3 的场景预算/来源处理
            case "meta" -> new SceneBudget(false, false, false, true, 0, 0, 3); // 匹配分支 case "meta" -> new SceneBudget(false, false, false, true, 0, 0, 3); 的场景预算/来源处理
            case "mail" -> new SceneBudget(false, false, false, false, 0, 0, 3); // 匹配分支 case "mail" -> new SceneBudget(false, false, false, false, 0, 0, 3); 的场景预算/来源处理
            case "shop" -> new SceneBudget(true, false, false, false, Math.min(invDefault, 32), 0, 3); // 匹配分支 case "shop" -> new SceneBudget(true, false, false, false, Math.min(inv 的场景预算/来源处理
            /**
             * 匹配分支 default -> new SceneBudget(false, false, false, true, 0, 0, 3); 的场景预算/来源处理
             */
            default -> new SceneBudget(false, false, false, true, 0, 0, 3);
        };
    }
    /**
     * 投影活跃任务到上下文
     */
    private List<Map<String, Object>> projectActiveQuests(List<QuestProgressEntity> quests, int maxQuests) {
        // 使用数组列表收集投影后的任务记录。
        List<Map<String, Object>> questSummaries = new ArrayList<>();
        // quests 为空时直接返回空列表，不继续遍历。
        if (quests == null) {
            return List.copyOf(questSummaries); // 返回：List.copyOf(questSummaries)
        }
        // maxQuests 至少为 1，防止无意义的 0 上限。
        int limit = Math.max(1, maxQuests);
        // 只投影进行中和待提交任务。
        for (QuestProgressEntity q : quests) {
            // 空任务进度直接跳过。
            if (q == null) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 仅保留关键任务状态，减少上下文噪音。
            if (q.getStatus() != CoachRuleEngine.QUEST_STATUS_IN_PROGRESS
                    && q.getStatus() != CoachRuleEngine.QUEST_STATUS_READY_SUBMIT) { // 延续上一行布尔条件
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 每条任务只保留 questId 和 status 两个白名单字段。
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("questId", q.getQuestId()); // 在 projectActiveQuests 中调用：row.put("questId", q.getQuestId());
            row.put("status", q.getStatus()); // 在 projectActiveQuests 中调用：row.put("status", q.getStatus());
            questSummaries.add(row); // 在 projectActiveQuests 中调用：questSummaries.add(row);
            // 达到预算上限后立即停止，避免任务过多拖大上下文。
            if (questSummaries.size() >= limit) {
                break; // 已达截断/命中条件，结束循环
            }
        }
        // 返回不可变列表，供上层安全复用。
        return List.copyOf(questSummaries);
    }
    /**
     * 投影背包到上下文
     */
    private InventoryProjection projectInventory(PlayerData playerData, int maxSlots) {
        // slots 保存被允许进入上下文的背包条目。
        List<Map<String, Object>> slots = new ArrayList<>();
        // total 用于记录真实背包条目数，不受投影上限影响。
        int total = 0;
        // truncated 标记明细是否被裁剪。
        boolean truncated = false;
        // 限额小于 0 时折叠为 0。
        int limit = Math.max(0, maxSlots);
        // 没有 playerData 或物品列表时直接返回空投影。
        if (playerData == null || playerData.getItems() == null) {
            return new InventoryProjection(List.copyOf(slots), 0, false); // 返回：new InventoryProjection(List.copyOf(slots), 0, false)
        }
        // 遍历所有物品实体，只保留未丢弃的有效条目。
        for (GameItemEntity item : playerData.getItems()) {
            // 空物品或已丢弃物品不进入上下文。
            if (item == null || item.isDiscarded()) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // total 记录真实有效物品数。
            total++;
            // 达到投影上限时，只累计 total，不再写入具体条目。
            if (limit <= 0 || slots.size() >= limit) {
                truncated = true; // 赋值 truncated，供 projectInventory 使用
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 背包字段只保留 itemId、type、count、level、promotion、rank 和 locked。
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", item.getItemId()); // 在 projectInventory 中调用：row.put("itemId", item.getItemId());
            row.put("type", item.getType()); // 在 projectInventory 中调用：row.put("type", item.getType());
            row.put("count", item.getCount()); // 在 projectInventory 中调用：row.put("count", item.getCount());
            row.put("level", item.getLevel()); // 在 projectInventory 中调用：row.put("level", item.getLevel());
            row.put("promotion", item.getPromotion()); // 在 projectInventory 中调用：row.put("promotion", item.getPromotion());
            row.put("rank", item.getRank()); // 在 projectInventory 中调用：row.put("rank", item.getRank());
            row.put("locked", item.isLocked()); // 在 projectInventory 中调用：row.put("locked", item.isLocked());
            // equipAvatarId 仅在有装备归属时才透出，避免空字段污染。
            if (item.getEquipAvatarId() != null) {
                row.put("equipAvatarId", item.getEquipAvatarId()); // 在 projectInventory 中调用：row.put("equipAvatarId", item.getEquipAvatarId());
            }
            slots.add(row); // 在 projectInventory 中调用：slots.add(row);
        }
        // 返回冻结后的投影结构。
        return new InventoryProjection(List.copyOf(slots), total, truncated);
    }
    /**
     * 投影角色列表到上下文
     */
    private List<Map<String, Object>> projectAvatars(PlayerData playerData,
                                                     int playerId, // 续写 projectAvatars 的参数列表
                                                     int maxAvatars, // 续写 projectAvatars 的参数列表
                                                     int maxTalentsPerAvatar) {
        // 角色投影同样使用 list 收集。
        List<Map<String, Object>> avatars = new ArrayList<>();
        // 缺少角色列表或预算为 0 时直接返回空。
        if (playerData == null || playerData.getAvatars() == null || maxAvatars <= 0) {
            return List.copyOf(avatars); // 返回：List.copyOf(avatars)
        }
        // avatarLimit 至少 1，避免预算被错误折叠成 0 后仍继续写入。
        int avatarLimit = Math.max(1, maxAvatars);
        // talentLimit 为每个角色最多投影的天赋条数。
        int talentLimit = Math.max(0, maxTalentsPerAvatar);
        // 遍历玩家拥有的角色，按顺序投影到上下文里。
        for (AvatarEntity avatar : playerData.getAvatars()) {
            // 空角色直接跳过。
            if (avatar == null) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 超过上限后立即停止，避免角色明细太长。
            if (avatars.size() >= avatarLimit) {
                break; // 已达截断/命中条件，结束循环
            }
            // 先计算属性面板，再决定写入哪些字段。
            AttributeCalculator.AvatarAttributes attrs = attributeCalculator.calculate(avatar);
            // 使用 LinkedHashMap 保证输出字段顺序稳定。
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("avatarId", avatar.getAvatarId()); // 在 projectAvatars 中调用：row.put("avatarId", avatar.getAvatarId());
            row.put("level", avatar.getLevel()); // 在 projectAvatars 中调用：row.put("level", avatar.getLevel());
            row.put("exp", avatar.getExp()); // 在 projectAvatars 中调用：row.put("exp", avatar.getExp());
            row.put("promotion", avatar.getPromotion()); // 在 projectAvatars 中调用：row.put("promotion", avatar.getPromotion());
            row.put("rank", avatar.getRank()); // 在 projectAvatars 中调用：row.put("rank", avatar.getRank());
            row.put("locked", avatar.isLocked()); // 在 projectAvatars 中调用：row.put("locked", avatar.isLocked());
            row.put("hp", attrs.hp()); // 在 projectAvatars 中调用：row.put("hp", attrs.hp());
            row.put("atk", attrs.atk()); // 在 projectAvatars 中调用：row.put("atk", attrs.atk());
            row.put("def", attrs.def()); // 在 projectAvatars 中调用：row.put("def", attrs.def());
            row.put("spd", attrs.spd()); // 在 projectAvatars 中调用：row.put("spd", attrs.spd());
            // talents 字段继续走独立投影，受每角色天赋上限控制。
            row.put("talents", projectTalents(playerId, avatar.getAvatarId(), talentLimit));
            avatars.add(row); // 在 projectAvatars 中调用：avatars.add(row);
        }
        // 返回只读角色投影列表。
        return List.copyOf(avatars);
    }
    /**
     * 投影天赋到上下文
     */
    private List<Map<String, Object>> projectTalents(int playerId, int avatarId, int maxTalents) {
        // playerId 非正或天赋数上限非正时，直接返回空列表。
        if (playerId <= 0 || maxTalents <= 0) {
            return List.of(); // 无命中结果，向上层返回空以便继续降级
        }
        // 查询指定玩家和角色的天赋列表。
        List<AvatarTalentEntity> talents = talentApplicationService.listTalents(playerId, avatarId);
        // 没有天赋时返回空，避免上下文字段噪声。
        if (talents == null || talents.isEmpty()) {
            return List.of(); // 无命中结果，向上层返回空以便继续降级
        }
        // rows 保存投影后的天赋白名单字段。
        List<Map<String, Object>> rows = new ArrayList<>();
        // 遍历天赋列表，最多取 maxTalents 条。
        for (AvatarTalentEntity talent : talents) {
            // 空天赋直接跳过。
            if (talent == null) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 达到投影上限后不再继续。
            if (rows.size() >= maxTalents) {
                break; // 已达截断/命中条件，结束循环
            }
            // 天赋只公开 id、等级和激活状态。
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("talentId", talent.getTalentId()); // 在 projectTalents 中调用：row.put("talentId", talent.getTalentId());
            row.put("level", talent.getLevel()); // 在 projectTalents 中调用：row.put("level", talent.getLevel());
            row.put("activated", talent.isActivated()); // 在 projectTalents 中调用：row.put("activated", talent.isActivated());
            rows.add(row); // 在 projectTalents 中调用：rows.add(row);
        }
        // 返回冻结后的天赋投影。
        return List.copyOf(rows);
    }
    /**
     * 构建监控摘要文本
     */
    private static String buildMonitoringSummary(InventoryProjection inventory,
                                                 List<Map<String, Object>> avatars,
                                                 String usageSummary) {
        // 使用 StringBuilder 拼接监测摘要，避免临时字符串过多。
        StringBuilder sb = new StringBuilder();
        // 先写背包物品总数，告诉模型当前库存量级。
        sb.append("背包物品 ").append(inventory.totalCount()).append(" 个");
        // 如果背包被截断，要明确告诉上层这是部分投影。
        if (inventory.truncated()) {
            sb.append("（已截断投影）"); // 在 buildMonitoringSummary 中调用：sb.append("（已截断投影）");
        }
        // 再写角色总数，为养成/阵容场景提供规模信息。
        sb.append("；角色 ").append(avatars.size()).append(" 个");
        // 角色不为空时，附加最低等级角色作为成长薄弱点。
        if (!avatars.isEmpty()) {
            // 默认把第一个角色当作比较基准。
            Map<String, Object> weakest = avatars.get(0);
            // 遍历所有投影角色，寻找等级最低的一位。
            for (Map<String, Object> avatar : avatars) {
                // 安全取出 level 字段，缺失则按 0 处理。
                int level = ((Number) avatar.getOrDefault("level", 0)).intValue();
                // 同样读取当前 weakest 的等级作为比较对象。
                int weakestLevel = ((Number) weakest.getOrDefault("level", 0)).intValue();
                // 更低等级的角色会成为新的薄弱项。
                if (level < weakestLevel) {
                    weakest = avatar; // 赋值 weakest，供 buildMonitoringSummary 使用
                }
            }
            // 低等级角色信息帮助解释“该先养谁”。
            sb.append("；最低等级角色 avatarId=")
                    .append(weakest.get("avatarId")) // 续写 buildMonitoringSummary 的参数列表
                    .append(" Lv.") // 续写 buildMonitoringSummary 的参数列表
                    .append(weakest.get("level")) // 续写 buildMonitoringSummary 的参数列表
                    .append(" 属性 ATK=") // 续写 buildMonitoringSummary 的参数列表
                    .append(weakest.get("atk")) // 续写 buildMonitoringSummary 的参数列表
                    .append(" HP=") // 续写 buildMonitoringSummary 的参数列表
                    .append(weakest.get("hp")); // 续写 buildMonitoringSummary 的参数列表
        }
        // 热门角色摘要存在时，也一并拼入监测摘要。
        if (usageSummary != null && !usageSummary.isBlank()) {
            sb.append("；").append(usageSummary); // 在 buildMonitoringSummary 中调用：sb.append("；").append(usageSummary);
        }
        // 返回用于 prompt 的中文监测句子。
        return sb.toString();
    }
    /**
     * 场景上下文预算配置
     */
    private record SceneBudget(boolean includeInventory,
                               boolean includeAvatars, // 续写 SceneBudget 的参数列表
                               boolean includeTalents, // 续写 SceneBudget 的参数列表
                               boolean includeMeta, // 续写 SceneBudget 的参数列表
                               int maxInventory, // 续写 SceneBudget 的参数列表
                               int maxAvatars, // 续写 SceneBudget 的参数列表
                               int maxQuests) {
    }
    /**
     * 背包投影结果
     */
    private record InventoryProjection(List<Map<String, Object>> slots, int totalCount, boolean truncated) {
    }
}
