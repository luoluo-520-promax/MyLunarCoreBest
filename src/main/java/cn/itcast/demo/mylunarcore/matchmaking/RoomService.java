// 房间状态管理：匹配成局后的临时编排表，维护成员 ready 态与 room status
package cn.itcast.demo.mylunarcore.matchmaking;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 房间状态管理服务。
 * 匹配完成后将队列玩家转化为 Room 快照；status=1 等待准备，status=2 全员 ready 可开战。
 */
@Service // 注册为 Spring 单例 Bean，在 JVM 内存中维护匹配成局后的房间快照
public class RoomService {

    /** 房间成员快照：playerId 标识队员，ready 表示该队员是否已点击准备按钮。 */
    public record RoomMember(int playerId, boolean ready) {}

    /** 房间快照：roomId 全局唯一、mode 匹配模式、status 房间阶段、members 不可变成员列表。 */
    public record Room(long roomId, int mode, int status, List<RoomMember> members) {}

    /** 全局递增房间 ID 序列；AtomicLong 保证多线程并发 createRoom 时不会产生重复 roomId。 */
    private final AtomicLong roomIdSeq = new AtomicLong(1);
    /** roomId → 房间快照；ConcurrentHashMap 支持多玩家并发读写准备态。 */
    private final Map<Long, Room> rooms = new ConcurrentHashMap<>();
    /** playerId → 当前所属 roomId；O(1) 反查玩家所在房间，供 findRoomByPlayer 与进战门禁使用。 */
    private final Map<Integer, Long> playerRoom = new ConcurrentHashMap<>();

    /**
     * 采纳对象池生成的房间快照写入索引（避免二次分配 roomId）。
     */
    public Room adoptPooledRoom(Room room, List<MatchQueue.QueueEntry> entries) {
        if (room == null) {
            return createRoom(1, entries);
        }
        rooms.put(room.roomId(), room);
        for (MatchQueue.QueueEntry entry : entries) {
            playerRoom.put(entry.playerId(), room.roomId());
        }
        return room;
    }

    /**
     * 根据匹配结果创建房间。
     * MatchmakingService 凑齐 DEFAULT_TEAM_SIZE 人后调用；初始 status=1，所有成员 ready=false。
     */
    public Room createRoom(int mode, List<MatchQueue.QueueEntry> entries) {
        long roomId = roomIdSeq.getAndIncrement(); // 分配新的全局唯一 roomId
        List<RoomMember> members = new ArrayList<>(); // 构建初始成员列表，全员默认未准备
        for (MatchQueue.QueueEntry entry : entries) {
            members.add(new RoomMember(entry.playerId(), false)); // 新成局成员 ready=false，等待客户端确认
            // 写入 playerId→roomId 归属，后续 findRoomByPlayer 与 ChallengeMatchCoordinator 可直接定位
            playerRoom.put(entry.playerId(), roomId);
        }
        // status=1 表示房间已创建但仍在等待准备阶段；List.copyOf 保证 members 对外不可变
        Room room = new Room(roomId, mode, 1, List.copyOf(members));
        rooms.put(roomId, room); // 将房间快照写入全局 rooms 映射
        return room; // 返回新建房间，供调用方或协议层推送通知
    }

    /**
     * 按玩家 uid 反查其当前所属房间。
     * 玩家未在任意匹配房间中（已解散或未成局）时返回 null。
     */
    public Room findRoomByPlayer(int playerId) {
        Long roomId = playerRoom.get(playerId); // 先查玩家归属的 roomId
        return roomId == null ? null : rooms.get(roomId); // 再取 roomId 对应的房间快照；映射不一致时可能为 null
    }

    /** 按 roomId 查询房间快照；不存在返回 null。 */
    public Room findRoom(long roomId) {
        return rooms.get(roomId);
    }

    /**
     * 更新指定玩家在房间内的准备状态。
     * 采用不可变快照模式：重建 Room 写回 Map；当全部成员 ready 时 status 置为 2。
     *
     * @return 更新后的 Room；roomId 不存在时 null
     */
    public Room setReady(int playerId, long roomId, boolean ready) {
        Room room = rooms.get(roomId); // 读取当前房间快照
        if (room == null) {
            return null; // roomId 无效或房间已被清理
        }
        List<RoomMember> updated = new ArrayList<>(); // 存放更新后的成员列表
        for (RoomMember member : room.members()) {
            // 仅修改目标玩家的 ready 位，其他成员保持原 ready 状态不变
            updated.add(member.playerId() == playerId
                    ? new RoomMember(playerId, ready)
                    : member);
        }
        // 检查是否所有成员均已 ready；是则 status=2（可开战），否则保持原 status（通常为 1）
        boolean allReady = updated.stream().allMatch(RoomMember::ready);
        Room next = new Room(room.roomId(), room.mode(), allReady ? 2 : room.status(), List.copyOf(updated));
        rooms.put(roomId, next); // 用新快照替换旧快照，保证并发读到的状态一致
        return next; // 返回最新房间状态，供协议层推送 MatchRoomScNotify
    }
}
