package cn.itcast.demo.mylunarcore.battle;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buff → 属性增量模板目录。未登记的 Buff 视为零影响，避免热路径查库。
 */
@Component
public class BuffModifierCatalog {

    private final Map<Integer, CombatAttributeSheet.BuffDelta> catalog = new ConcurrentHashMap<>();

    public BuffModifierCatalog() {
        // 常见增益占位：可被配置热更覆盖
        catalog.put(1001, new CombatAttributeSheet.BuffDelta(20, 0, 0, 5, 0, 0, 0)); // 攻击提升
        catalog.put(1002, new CombatAttributeSheet.BuffDelta(0, 15, 0, 0, 8, 0, 0)); // 防御提升
        catalog.put(1003, new CombatAttributeSheet.BuffDelta(0, 0, 5, 0, 0, 0, 0));  // 速度提升
        catalog.put(2001, new CombatAttributeSheet.BuffDelta(-15, 0, 0, -5, 0, 0, 0)); // 攻击降低
        catalog.put(2002, new CombatAttributeSheet.BuffDelta(0, -10, 0, 0, -5, 0, 0)); // 防御降低
    }

    public CombatAttributeSheet.BuffDelta perStack(int buffId) {
        return catalog.getOrDefault(buffId, CombatAttributeSheet.BuffDelta.zero());
    }

    public void register(int buffId, CombatAttributeSheet.BuffDelta delta) {
        if (delta != null) {
            catalog.put(buffId, delta);
        }
    }
}
