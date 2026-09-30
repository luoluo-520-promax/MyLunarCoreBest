package cn.itcast.demo.mylunarcore.matchmaking;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MatchSession / Room 对象池：万人秒排时复用可变壳对象，降低 YGC 风暴。
 */
@Component
public class MatchObjectPools {

    /** 可变匹配会话壳：出池后填充，归还前 reset。 */
    public static final class MatchSession {
        private int playerId;
        private int mode;
        private int level;
        private int power;
        private long enqueueTime;
        private int elo;

        public void assign(int playerId, int mode, int level, int power, long enqueueTime, int elo) {
            this.playerId = playerId;
            this.mode = mode;
            this.level = level;
            this.power = power;
            this.enqueueTime = enqueueTime;
            this.elo = elo;
        }

        public void reset() {
            playerId = 0;
            mode = 0;
            level = 0;
            power = 0;
            enqueueTime = 0;
            elo = 0;
        }

        public int playerId() { return playerId; }
        public int mode() { return mode; }
        public int level() { return level; }
        public int power() { return power; }
        public long enqueueTime() { return enqueueTime; }
        public int elo() { return elo; }

        public MatchQueue.QueueEntry toQueueEntry() {
            return new MatchQueue.QueueEntry(playerId, mode, level, power, enqueueTime);
        }
    }

    /** 可变房间壳：成员列表可复用，减少临时 ArrayList。 */
    public static final class PooledRoom {
        private long roomId;
        private int mode;
        private int status;
        private final List<RoomService.RoomMember> members = new ArrayList<>(4);

        public void assign(long roomId, int mode, int status, List<RoomService.RoomMember> src) {
            this.roomId = roomId;
            this.mode = mode;
            this.status = status;
            members.clear();
            if (src != null) {
                members.addAll(src);
            }
        }

        public void reset() {
            roomId = 0;
            mode = 0;
            status = 0;
            members.clear();
        }

        public RoomService.Room toImmutable() {
            return new RoomService.Room(roomId, mode, status, List.copyOf(members));
        }

        public long roomId() { return roomId; }
        public List<RoomService.RoomMember> members() { return members; }
    }

    private final GenericObjectPool<MatchSession> sessionPool;
    private final GenericObjectPool<PooledRoom> roomPool;
    private final AtomicLong roomIdSeq = new AtomicLong(1);

    public MatchObjectPools() {
        GenericObjectPoolConfig<MatchSession> sc = new GenericObjectPoolConfig<>();
        sc.setMaxTotal(2048);
        sc.setMaxIdle(512);
        sc.setMinIdle(32);
        sessionPool = new GenericObjectPool<>(new BasePooledObjectFactory<>() {
            @Override
            public MatchSession create() {
                return new MatchSession();
            }

            @Override
            public PooledObject<MatchSession> wrap(MatchSession obj) {
                return new DefaultPooledObject<>(obj);
            }

            @Override
            public void passivateObject(PooledObject<MatchSession> p) {
                p.getObject().reset();
            }
        }, sc);

        GenericObjectPoolConfig<PooledRoom> rc = new GenericObjectPoolConfig<>();
        rc.setMaxTotal(1024);
        rc.setMaxIdle(256);
        rc.setMinIdle(16);
        roomPool = new GenericObjectPool<>(new BasePooledObjectFactory<>() {
            @Override
            public PooledRoom create() {
                return new PooledRoom();
            }

            @Override
            public PooledObject<PooledRoom> wrap(PooledRoom obj) {
                return new DefaultPooledObject<>(obj);
            }

            @Override
            public void passivateObject(PooledObject<PooledRoom> p) {
                p.getObject().reset();
            }
        }, rc);
    }

    public MatchSession borrowSession() throws Exception {
        return sessionPool.borrowObject();
    }

    public void returnSession(MatchSession session) {
        if (session != null) {
            sessionPool.returnObject(session);
        }
    }

    public PooledRoom borrowRoom() throws Exception {
        return roomPool.borrowObject();
    }

    public void returnRoom(PooledRoom room) {
        if (room != null) {
            roomPool.returnObject(room);
        }
    }

    public long nextRoomId() {
        return roomIdSeq.getAndIncrement();
    }

    @PreDestroy
    public void close() {
        sessionPool.close();
        roomPool.close();
    }
}
