package cn.itcast.demo.mylunarcore.world; // world 包：无缝开放世界的 Cell 分片、边界移交与实时战斗实验模块

/**
 * 无缝世界 Cell 坐标值对象（record），表示 XZ 平面上的均匀网格单元。
 * <p>
 * 与 {@link cn.itcast.demo.mylunarcore.center.SceneRegistry#zoneId}（plane+floor）两套坐标系正交配合：
 * Zone 仍是玩法/物理分区（平面+楼层）；Cell 则用于同一 Zone 内部的流式分片加载与跨边界移交（handoff）。
 * 采用不可变 record 语义，天然具备相等性判断（equals/hashCode），便于作为 HashMap/ConcurrentHashMap 的键。
 * <p>
 * 网格由 cellSize 决定物理粒度（默认 64 世界单位一格），坐标原点取世界坐标向下取整，
 * 保证同一物理位置在任何时间点计算出的 Cell 完全一致，从而支撑"玩家移动→跨格检测→边界事件"的确定性判定。
 *
 * @param cellX 网格横向索引（由世界 X 坐标除以格宽并向下取整得到，可为负）
 * @param cellZ 网格纵向索引（由世界 Z 坐标除以格宽并向下取整得到，可为负）
 */
public record CellCoord(int cellX, int cellZ) {

    /**
     * 根据世界坐标与格宽计算所在 Cell 坐标。
     * <p>
     * 数学约定：以 cellSize 为单位的世界坐标除以格宽向下取整，即 {@code floor(worldX / size)}。
     * 使用 {@code Math.floor} 而非截断取整，确保负数世界坐标也能得到正确、无偏的格索引。
     *
     * @param worldX   世界空间 X 坐标（浮点）
     * @param worldZ   世界空间 Z 坐标（浮点）
     * @param cellSize 单个 Cell 的边长（世界单位）；小于等于 0 时回退到默认 64
     * @return 该物理位置对应的 {@link CellCoord}
     */
    public static CellCoord of(float worldX, float worldZ, float cellSize) {
        float size = cellSize <= 0f ? 64f : cellSize; // 非法格宽按默认 64 处理，避免除零/负格宽
        int cx = (int) Math.floor(worldX / size); // X 轴格索引：向下取整保证边界归属稳定
        int cz = (int) Math.floor(worldZ / size); // Z 轴格索引：同上
        return new CellCoord(cx, cz); // 返回不可变坐标对象
    }

    /**
     * 生成该 Cell 的字符串键 "{cellX}:{cellZ}"，用于 Map 键、Redis key 或日志。
     * <p>
     * 使用冒号分隔两个整数索引，天然无歧义（例如 "3:-2"），
     * 可被 {@link cn.itcast.demo.mylunarcore.world.CellBoundaryHandoffService} 的 lastCellByUid 映射直接采用。
     *
     * @return 形如 "cellX:cellZ" 的稳定字符串标识
     */
    public String key() {
        return cellX + ":" + cellZ; // 简单字符串拼接，作为唯一键
    }
}
