// 模拟宇宙房间静态配置值对象所在包
package cn.itcast.demo.mylunarcore.model;

// Lombok：为 final 字段生成 getter
import lombok.Getter;

/**
 * 模拟宇宙房间静态配置（由 L2 加载后缓存在 L1，按房间 id 延迟加载，避免一次性载入全部房间）。
 */
@Getter // 只暴露 getter，字段不可变
public class RogueRoomData {
    private final int roomId;   // 房间唯一 id
    private final int roomType; // 房间类型（战斗/事件/商店等）
    private final int posX;     // 地图 X 坐标
    private final int posY;     // 地图 Y 坐标

    /**
     * 构造不可变房间配置快照。
     */
    public RogueRoomData(int roomId, int roomType, int posX, int posY) {
        this.roomId = roomId;     // 赋值房间 id
        this.roomType = roomType; // 赋值房间类型
        this.posX = posX;         // 赋值 X
        this.posY = posY;         // 赋值 Y
    }
}
