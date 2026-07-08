package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 波次运行时对象工厂。
 * <p>它负责把数据库里的波次配置转换成运行时可直接使用的 `WaveRuntime` 和 `MonsterRuntime` 对象，
 * 让战斗逻辑可以直接拿到怪物列表，而不必再关心原始 JSON 配置长什么样。</p>
 */
public final class BattleMonsterWaveSimpleFactory {

    // 统一复用 JSON 解析器，用来把 monstersJson 里的数组解析成怪物 ID 列表。
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 工具类不允许实例化。
    private BattleMonsterWaveSimpleFactory() {
    }

    /**
     * 根据一条波次配置创建对应的运行时波次对象。
     * <p>这里会先解析怪物 ID 列表，再按波次等级计算每只怪物的基础生命值，最后组装成 `WaveRuntime`。</p>
     */
    public static WaveRuntime createWaveFromConfig(BattleMonsterWaveRepository.WaveConfig cfg) {
        List<Integer> monsterIds = parseMonsterIds(cfg.getMonstersJson()); // 从 JSON 中取出本波次有哪些怪物。
        int level = cfg.getCustomLevel() != 0 ? cfg.getCustomLevel() : 1; // 如果没有配置等级，就默认按 1 级处理。
        List<MonsterRuntime> monsters = new ArrayList<>();
        for (Integer monsterId : monsterIds) {
            int maxHp = Math.max(1, level * 100); // 演示性的血量公式：等级越高，生命值越高。
            monsters.add(new MonsterRuntime(monsterId, level, maxHp)); // 为每只怪物创建一个运行时实例。
        }
        return new WaveRuntime(cfg.getWaveOrder(), monsters); // 生成这一波的完整运行时快照。
    }

    /**
     * 解析 `monstersJson`。
     * <p>配置格式预期是一个 JSON 数组，例如 `[101,102,103]`；如果格式不合法、不是数组，或者解析失败，就返回空列表，
     * 这样上层逻辑就不会因为配置问题直接抛异常。</p>
     */
    private static List<Integer> parseMonsterIds(String monstersJson) {
        if (monstersJson == null || monstersJson.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JsonNode node = MAPPER.readTree(monstersJson);
            if (!node.isArray()) {
                return Collections.emptyList();
            }
            List<Integer> ids = new ArrayList<>();
            for (JsonNode n : node) {
                if (n.isInt()) {
                    ids.add(n.intValue());
                } else if (n.isNumber()) {
                    ids.add(n.asInt()); // 兼容配置里写成数值类型但不是整数的情况。
                }
            }
            return ids;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
