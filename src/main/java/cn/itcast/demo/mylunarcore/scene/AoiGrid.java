// AOI（Area of Interest，兴趣区域）网格索引所在包：按空间格子快速筛出邻近玩家
package cn.itcast.demo.mylunarcore.scene;

// 可变哈希集合：聚合九宫格内各格子的玩家 uid
import java.util.HashSet;
// 映射接口：格子键 → 玩家集合、玩家 uid → 所在格子
import java.util.Map;
// 集合接口：单格内的玩家 uid 集合
import java.util.Set;
// 线程安全哈希结构：支持多 Netty 工作线程并发更新玩家格子归属
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于均匀格子划分的 AOI 空间索引。
 * <p>
 * 将 XZ 平面按 {@code cellSize} 切分为网格，每个玩家归属一个格子；
 * 查询邻近玩家时只扫描中心格及其周围 8 格（3×3 九宫格），时间复杂度 O(邻格玩家数)，
 * 远优于遍历整个 Zone 内全部在线玩家。支持按密度动态调整格子边长。
 */
public class AoiGrid {

    /** 单格边长（游戏世界单位），决定 AOI 查询半径粒度：值越大邻近范围越广、单格玩家越密集。 */
    private volatile float cellSize;

    /**
     * 玩家 uid → 当前所在格子的编码键（long）。
     * 用于玩家移动时判断是否需要从旧格子迁移到新格子。
     */
    private final Map<Long, Long> playerCells = new ConcurrentHashMap<>();

    /**
     * 格子编码键 → 该格内所有玩家 uid 集合。
     * 邻近查询时按格子键直接命中，避免全量扫描 playerCells。
     */
    private final Map<Long, Set<Long>> cellPlayers = new ConcurrentHashMap<>();

    /**
     * 构造 AOI 网格。
     *
     * @param cellSize 格子边长，由 {@link ZoneContext} 传入，与游戏世界坐标单位一致
     */
    public AoiGrid(float cellSize) {
        this.cellSize = Math.max(4f, cellSize);
    }

    public float getCellSize() {
        return cellSize;
    }

    /**
     * 按密度动态调整格子边长；变化超过阈值时重建索引。
     *
     * @return true 表示发生了重建
     */
    public synchronized boolean adjustCellSize(float newSize, Map<Long, float[]> playerPositionsXz) {
        float clamped = Math.max(4f, newSize);
        if (Math.abs(clamped - cellSize) < 0.5f) {
            return false;
        }
        this.cellSize = clamped;
        playerCells.clear();
        cellPlayers.clear();
        if (playerPositionsXz != null) {
            for (Map.Entry<Long, float[]> e : playerPositionsXz.entrySet()) {
                float[] xz = e.getValue();
                if (xz != null && xz.length >= 2) {
                    update(e.getKey(), xz[0], xz[1]);
                }
            }
        }
        return true;
    }

    /** 当前最密集格子的玩家数，供动态 AOI 决策。 */
    public int maxPlayersInAnyCell() {
        int max = 0;
        for (Set<Long> set : cellPlayers.values()) {
            if (set != null) {
                max = Math.max(max, set.size());
            }
        }
        return max;
    }

    /**
     * 更新玩家在 AOI 网格中的位置：计算新格子键，若与旧格子不同则从旧格移除并加入新格。
     * 同格内移动时仅覆盖 playerCells 映射，不触发格子间迁移。
     *
     * @param playerUid 玩家 uid
     * @param x         世界坐标 X（水平轴）
     * @param z         世界坐标 Z（水平纵深轴，俯视角场景中参与格子划分）
     */
    public void update(long playerUid, float x, float z) {
        long newCell = cellKey(x, z);
        // put 返回旧格子键：null 表示玩家首次进入网格，非 null 且不等于 newCell 表示跨格移动
        Long oldCell = playerCells.put(playerUid, newCell);
        if (oldCell != null && !oldCell.equals(newCell)) {
            Set<Long> oldSet = cellPlayers.get(oldCell);
            if (oldSet != null) {
                oldSet.remove(playerUid);
            }
        }
        // computeIfAbsent 保证新格子首次出现时创建线程安全的 ConcurrentHashMap.newKeySet()
        cellPlayers.computeIfAbsent(newCell, k -> ConcurrentHashMap.newKeySet()).add(playerUid);
    }

    /**
     * 玩家离开 Zone 时从 AOI 索引中完全摘除：清除 uid→格子 与 格子→uid 双向映射。
     *
     * @param playerUid 待移除的玩家 uid
     */
    public void remove(long playerUid) {
        Long cell = playerCells.remove(playerUid);
        if (cell != null) {
            Set<Long> set = cellPlayers.get(cell);
            if (set != null) {
                set.remove(playerUid);
            }
        }
    }

    /**
     * 查询以 (x, z) 为中心、3×3 九宫格范围内的所有玩家 uid，并排除查询者自身。
     * 九宫格覆盖范围约为 {@code [center - cellSize, center + 2*cellSize]} 的矩形邻域。
     *
     * @param selfUid 查询发起者 uid，会从结果中剔除以避免自己收到自己的同步包
     * @param x       查询中心点世界坐标 X
     * @param z       查询中心点世界坐标 Z
     * @return 邻近玩家 uid 集合（不含 selfUid）
     */
    public Set<Long> nearby(long selfUid, float x, float z) {
        long center = cellKey(x, z);
        // 格子键高 32 位存 cx（列索引），低 32 位存 cz（行索引），此处拆包还原格子坐标
        int cx = (int) (center >> 32);
        int cz = (int) center;
        Set<Long> result = new HashSet<>();
        // 遍历中心格及上下左右对角共 9 个相邻格子
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                // 将 (cx+dx, cz+dz) 重新打包为格子键，与 cellKey 编码规则一致
                long key = (((long) (cx + dx)) << 32) | Integer.toUnsignedLong(cz + dz);
                Set<Long> players = cellPlayers.get(key);
                if (players != null) {
                    result.addAll(players);
                }
            }
        }
        result.remove(selfUid);
        return result;
    }

    /**
     * 将世界坐标 (x, z) 映射为格子编码键。
     * <p>
     * 算法：{@code cx = floor(x / cellSize)}，{@code cz = floor(z / cellSize)}，
     * 再将 cx 左移 32 位与 cz 按位或，把二维格子索引压缩进一个 long，作为 HashMap 键。
     * 使用 {@link Integer#toUnsignedLong(int)} 避免 cz 为负数时符号扩展破坏键的唯一性。
     *
     * @param x 世界坐标 X
     * @param z 世界坐标 Z
     * @return 格子唯一编码键
     */
    private long cellKey(float x, float z) {
        int cx = (int) Math.floor(x / cellSize);
        int cz = (int) Math.floor(z / cellSize);
        return (((long) cx) << 32) | Integer.toUnsignedLong(cz);
    }
}
