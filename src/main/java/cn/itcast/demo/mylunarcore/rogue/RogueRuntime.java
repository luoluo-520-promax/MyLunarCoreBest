// 单次模拟宇宙（Rogue）局内内存状态所在包
package cn.itcast.demo.mylunarcore.rogue;

// Rogue 协议消息类型
import cn.itcast.demo.mylunarcore.protocol.RogueSystemProto;
// 房间静态数据
import cn.itcast.demo.mylunarcore.model.RogueRoomData;
// 房间 L1 缓存
import cn.itcast.demo.mylunarcore.repo.RogueRoomCache;
// Lombok：为字段生成 getter
import lombok.Getter;

// 祝福列表
import java.util.ArrayList;
// 奇物 id 集合
import java.util.HashSet;
// 列表接口
import java.util.List;
// 集合接口
import java.util.Set;

/**
 * 单次 Rogue 副本内存状态：层数、波次、祝福/奇物、当前房间等，由 {@link cn.itcast.demo.mylunarcore.rogue.RogueNettyService} 推进。
 */
@Getter // 对外只读访问字段（集合本身仍可变）
public class RogueRuntime {
    private final int playerId;           // 玩家 id
    private final int rogueId;            // 玩法/赛季 id
    private final int difficulty;         // 难度
    private final RogueRoomCache rogueRoomCache; // 解析当前房间

    private volatile int status;          // 0=进行中，1=已完成，2=已放弃
    private volatile int floor;           // 当前层
    private volatile int wave;            // 当前波次
    private volatile int score;           // 本局积分
    private volatile int virtualCurrency; // 局内货币

    private volatile int currentRoomId;   // 当前所在房间 id

    private final List<RogueSystemProto.RogueBlessing> blessings = new ArrayList<>(); // 已选祝福
    private final Set<Integer> miracles = new HashSet<>(); // 已获奇物 id

    /**
     * 新开一局时的初始状态。
     */
    public RogueRuntime(int playerId, int rogueId, int difficulty, RogueRoomCache rogueRoomCache) {
        this.playerId = playerId;
        this.rogueId = rogueId;
        this.difficulty = difficulty;
        this.rogueRoomCache = rogueRoomCache;
        this.status = 0;
        this.floor = 1;
        this.wave = 1;
        this.score = 0;
        this.virtualCurrency = 200; // 初始局内币
        this.currentRoomId = 5001;  // 起始房间
    }

    /**
     * 组装协议用的当前房间信息（含坐标与类型）。
     */
    public RogueSystemProto.RogueRoomInfo currentRoomInfo() {
        RogueRoomData data = rogueRoomCache.getOrLoad(currentRoomId);
        RogueSystemProto.RogueRoomPosition pos = RogueSystemProto.RogueRoomPosition.newBuilder()
                .setX(data.getPosX())
                .setY(data.getPosY())
                .build();
        return RogueSystemProto.RogueRoomInfo.newBuilder()
                .setRoomId(data.getRoomId())
                .setRoomType(data.getRoomType())
                .setPosition(pos)
                .build();
    }

    /**
     * 奇物 id 列表（排序后），用于下行协议。
     */
    public List<Integer> miraclesList() {
        return miracles.stream().sorted().toList();
    }

    /**
     * 移动到下一房间：波次+1，每 3 波升一层。
     */
    public void moveToRoom(int nextRoomId) {
        this.currentRoomId = nextRoomId;
        this.wave = Math.max(1, this.wave + 1);
        if (this.wave % 3 == 0) {
            this.floor = this.floor + 1;
        }
    }

    /**
     * 获得或叠层祝福：同 id 则 level+1，否则新增 level=1。
     */
    public void addBlessing(int blessingId) {
        for (int i = 0; i < blessings.size(); i++) {
            RogueSystemProto.RogueBlessing b = blessings.get(i);
            if (b.getId() == blessingId) {
                blessings.set(i, RogueSystemProto.RogueBlessing.newBuilder()
                        .setId(blessingId).setLevel(b.getLevel() + 1).build());
                return;
            }
        }
        blessings.add(RogueSystemProto.RogueBlessing.newBuilder().setId(blessingId).setLevel(1).build());
    }

    /** 获得奇物（去重由 Set 保证）。 */
    public void addMiracle(int miracleId) {
        miracles.add(miracleId);
    }

    /** 增加积分（不允许减到负数）。 */
    public void addScore(int add) {
        this.score = Math.max(0, this.score + Math.max(0, add));
    }

    /** 增加局内货币。 */
    public void addCurrency(int add) {
        this.virtualCurrency = Math.max(0, this.virtualCurrency + add);
    }

    /** 玩家主动放弃：状态置为已放弃。 */
    public void quit() {
        this.status = 2;
    }
}
