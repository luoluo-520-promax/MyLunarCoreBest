// 将玩家核心数值（等级、体力、货币等）写入统一同步协议的 Syncable 实现
package cn.itcast.demo.mylunarcore.player;

// 玩家领域聚合根
import cn.itcast.demo.mylunarcore.model.PlayerData;

// 玩家基础档案实体，承载等级、昵称、货币 JSON 等核心字段
import cn.itcast.demo.mylunarcore.model.PlayerEntity;

// 下行同步 Protobuf 消息定义
import cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto;

// Spring Bean 装配顺序：核心切片必须最先写入 builder
import org.springframework.core.annotation.Order;

// 声明为 Spring 组件
import org.springframework.stereotype.Component;

// 货币 map，供 protobuf putAllCurrency 批量写入
import java.util.Map;

/**
 * 玩家基础数值同步切片。
 * <p>
 * 负责将 {@link PlayerEntity} 中的等级、经验、体力、昵称、世界等级及货币 map
 * 写入 {@link PlayerSessionProto.PlayerUnifiedSyncScNotify} 的顶层标量字段。
 * </p>
 */
@Component // 注册为 Spring Bean
@Order(0) // 最高优先级：确保核心字段最先被各 Syncable 写入
public class PlayerCoreSyncable implements Syncable {

    @Override
    public void onSync(PlayerSessionProto.PlayerUnifiedSyncScNotify.Builder builder, PlayerData data) {
        PlayerEntity p = data.getPlayer(); // 取出玩家主实体引用
        if (p == null) { // 主实体尚未加载（极端情况下异步加载未完成）则无法同步
            return;
        }
        // 将数据库 JSON 格式的货币字段解析为 Map，与登录响应使用同一解析逻辑
        Map<Integer, Integer> currency = PlayerCurrencyHelper.parseCurrency(p.getCurrencyJson());
        builder.setLevel(Math.max(0, p.getLevel())) // 玩家等级，负值钳制
                .setExp(Math.max(0, (int) Math.min(Integer.MAX_VALUE, p.getExp()))) // 经验值，long 转 int 并防溢出
                .setStamina(Math.max(0, p.getStamina())) // 当前体力值
                .setNickname(p.getNickname() == null ? "" : p.getNickname()) // 昵称，null 安全
                .setWorldLevel(Math.max(0, p.getWorldLevel())) // 世界等级（影响怪物强度等）
                .putAllCurrency(currency); // 批量写入货币类型 id -> 数量的 map
    }
}
