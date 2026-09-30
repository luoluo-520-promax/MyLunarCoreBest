package cn.itcast.demo.mylunarcore.world;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cell 边界移交检测：玩家在同一 Zone（plane+floor）内跨网格 Cell 时生成 {@link HandoffEvent}。
 * <p>
 * 开关 {@code lunarcore.world.cell-handoff-enabled} 默认关闭；开启后主要打日志并回调
 * {@link HandoffListener}。真正跨游戏节点迁移仍走既有 MigrationTicket，本类不做会话导出。
 */
@Service
public class CellBoundaryHandoffService {

    // 场景业务日志
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_SCENE, CellBoundaryHandoffService.class);

    /**
     * 一次跨 Cell 事件快照。
     *
     * @param playerUid 玩家 UID
     * @param planeId   场景平面 ID（来自 SceneContext）
     * @param floorId   楼层 ID
     * @param from      移动前 Cell
     * @param to        移动后 Cell
     * @param x         接受后的世界坐标 X
     * @param y         世界坐标 Y
     * @param z         世界坐标 Z
     */
    public record HandoffEvent(long playerUid, int planeId, int floorId,
                               CellCoord from, CellCoord to, float x, float y, float z) {}

    /** 可选监听器：例如挂接遥测或预加载邻格资源。 */
    @FunctionalInterface
    public interface HandoffListener {
        void onCellHandoff(HandoffEvent event);
    }

    // 读取 cellSize、cell-handoff-enabled
    private final LunarCoreProperties properties;
    // 每名玩家上次所在 Cell，用于与本次坐标比较是否跨格
    private final Map<Long, CellCoord> lastCellByUid = new ConcurrentHashMap<>();
    // 外部注入的监听；volatile 保证可见性
    private volatile HandoffListener listener;

    public CellBoundaryHandoffService(LunarCoreProperties properties) {
        this.properties = properties;
    }

    /** 注册/替换跨格回调；传 null 可清空。 */
    public void setListener(HandoffListener listener) {
        this.listener = listener;
    }

    /**
     * 按配置格宽把世界 XZ 映射为 {@link CellCoord}。
     */
    public CellCoord currentCell(float x, float z) {
        return CellCoord.of(x, z, properties.getWorld().getCellSize());
    }

    /**
     * 服务器已接受玩家移动后调用。
     * <ul>
     *   <li>功能关闭或 scene 为空：仍更新 lastCell，返回 null；</li>
     *   <li>首帧或未跨格：返回 null；</li>
     *   <li>跨格：打 info 日志、回调 listener、返回事件。</li>
     * </ul>
     */
    public HandoffEvent onMoveAccepted(SceneContext scene, long playerUid, float x, float y, float z) {
        if (!properties.getWorld().isCellHandoffEnabled() || scene == null) {
            // 关闭时也维护 lastCell，避免下次开启时误判「从 null 跨到当前格」
            lastCellByUid.put(playerUid, currentCell(x, z));
            return null;
        }
        CellCoord next = currentCell(x, z);
        CellCoord prev = lastCellByUid.put(playerUid, next); // 原子替换并取旧值
        if (prev == null || prev.equals(next)) {
            return null; // 首次记录或仍在同一 Cell
        }
        HandoffEvent event = new HandoffEvent(playerUid, scene.getPlaneId(), scene.getFloorId(),
                prev, next, x, y, z);
        log.info("cell handoff uid={} plane={} floor={} from={} to={} pos=({},{},{})",
                playerUid, event.planeId(), event.floorId(), prev.key(), next.key(), x, y, z);
        HandoffListener l = listener;
        if (l != null) {
            try {
                l.onCellHandoff(event);
            } catch (Exception e) {
                // 监听失败不影响移动主流程
                log.warn("cell handoff listener failed, uid={}", playerUid, e);
            }
        }
        return event;
    }

    /** 玩家离线或切场景时清理 Cell 记忆，防止 UID 复用串数据。 */
    public void clear(long playerUid) {
        lastCellByUid.remove(playerUid);
    }
}
