package cn.itcast.demo.mylunarcore.player; // 主界面 ↔ 开始游戏流程协议门面

import cn.itcast.demo.mylunarcore.model.PlayerData; // 读取上次场景与坐标
import cn.itcast.demo.mylunarcore.model.PlayerEntity; // 存档 scene_id / pos
import cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto; // StartGame / ReturnMainMenu 消息
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto; // 复用 EnterScene 请求
import cn.itcast.demo.mylunarcore.scene.SceneContext; // 已在场景时回显坐标
import cn.itcast.demo.mylunarcore.scene.SceneManager; // 查询是否已在场景
import cn.itcast.demo.mylunarcore.scene.SceneNettyService; // 实际进入场景
import io.netty.channel.Channel; // 当前连接
import org.springframework.stereotype.Service; // Spring 服务

import java.math.BigDecimal; // 坐标高精度转 float

/**
 * 游戏主流程门面：主界面「开始游戏」、玩法中「返回主界面」。
 * <p>
 * 约定：登录成功后处于 {@link PlayerSessionState#HALL}（主界面）；
 * {@link #handleStartGame} 进入 SCENE；{@link #handleReturnMainMenu} 清玩法并回到 HALL；
 * 彻底退出进程/标题请走 {@link PlayerSessionService#handleLogout}。
 */
@Service
public class GameFlowNettyService {

    /** 成功 */
    public static final int RET_OK = 0;
    /** 未登录 */
    public static final int RET_NOT_LOGIN = 1;
    /** 当前不在主界面，无法开始（例如已在战斗中需先退出） */
    public static final int RET_NOT_IN_HALL = 2;
    /** 场景配置不存在或进入失败 */
    public static final int RET_ENTER_SCENE_FAILED = 3;
    /** 会话状态非法，无法回到主界面 */
    public static final int RET_STATE_INVALID = 4;

    /** 默认楼层：存档仅有 scene_id（plane）时使用 */
    private static final int DEFAULT_FLOOR_ID = 1;
    /** 默认入口 */
    private static final int DEFAULT_ENTRY_ID = 0;
    /** 无存档场景时的默认位面（与种子数据 10001 对齐） */
    private static final int DEFAULT_PLANE_ID = 10001;

    private final PlayerContextResolver contextResolver;
    private final GameSessionManager sessionManager;
    private final SceneManager sceneManager;
    private final SceneNettyService sceneNettyService;
    private final PlaySessionCleanupService playSessionCleanupService;

    public GameFlowNettyService(PlayerContextResolver contextResolver,
                                GameSessionManager sessionManager,
                                SceneManager sceneManager,
                                SceneNettyService sceneNettyService,
                                PlaySessionCleanupService playSessionCleanupService) {
        this.contextResolver = contextResolver;
        this.sessionManager = sessionManager;
        this.sceneManager = sceneManager;
        this.sceneNettyService = sceneNettyService;
        this.playSessionCleanupService = playSessionCleanupService;
    }

    /**
     * 主界面开始游戏：要求当前为 HALL，然后进入场景（请求指定或存档位置）。
     * <p>若已在 SCENE，幂等返回当前场景信息（retcode=0）。
     */
    public PlayerSessionProto.StartGameScRsp handleStartGame(PlayerSessionProto.StartGameCsReq req, Channel channel) {
        Long uid = contextResolver.resolveUid(channel).stream().boxed().findFirst().orElse(null);
        if (uid == null) {
            return PlayerSessionProto.StartGameScRsp.newBuilder().setRetcode(RET_NOT_LOGIN).build();
        }
        GameSession session = sessionManager.getOrNull(uid);
        if (session == null) {
            return PlayerSessionProto.StartGameScRsp.newBuilder().setRetcode(RET_NOT_LOGIN).build();
        }

        // 已在场景中：视为已开始，直接回显，避免重复 enter
        if (session.getSessionState() == PlayerSessionState.SCENE) {
            SceneContext ctx = sceneManager.getByPlayerUid(uid);
            if (ctx != null) {
                return buildStartOk(ctx.getPlaneId(), ctx.getFloorId(), ctx.getEntryId(),
                        ctx.getPlayerPos().getX(), ctx.getPlayerPos().getY(), ctx.getPlayerPos().getZ());
            }
        }

        if (session.getSessionState() != PlayerSessionState.HALL) {
            // 战斗/匹配等状态需先 ReturnMainMenu 或完成子系统退出
            return PlayerSessionProto.StartGameScRsp.newBuilder()
                    .setRetcode(RET_NOT_IN_HALL)
                    .setSessionState(toProtoState(session.getSessionState()))
                    .build();
        }

        EnterTarget target = resolveEnterTarget(req, session);
        SceneSystemProto.EnterSceneCsReq enterReq = SceneSystemProto.EnterSceneCsReq.newBuilder()
                .setPlaneId(target.planeId())
                .setFloorId(target.floorId())
                .setEntryId(target.entryId())
                .setPosX(target.x())
                .setPosY(target.y())
                .setPosZ(target.z())
                .build();
        SceneSystemProto.EnterSceneScRsp enterRsp = sceneNettyService.handleEnterScene(enterReq, channel);
        if (enterRsp.getRetcode() != 0) {
            return PlayerSessionProto.StartGameScRsp.newBuilder()
                    .setRetcode(RET_ENTER_SCENE_FAILED)
                    .setSessionState(toProtoState(session.getSessionState()))
                    .build();
        }
        SceneSystemProto.SceneLoadInfo info = enterRsp.getSceneInfo();
        return buildStartOk(info.getPlaneId(), info.getFloorId(), info.getEntryId(),
                info.getPosX(), info.getPosY(), info.getPosZ());
    }

    /**
     * 退出当前玩法并回到主界面 HALL，保持登录连接。
     */
    public PlayerSessionProto.ReturnMainMenuScRsp handleReturnMainMenu(
            PlayerSessionProto.ReturnMainMenuCsReq req, Channel channel) {
        Long uid = contextResolver.resolveUid(channel).stream().boxed().findFirst().orElse(null);
        if (uid == null) {
            return PlayerSessionProto.ReturnMainMenuScRsp.newBuilder().setRetcode(RET_NOT_LOGIN).build();
        }
        GameSession session = sessionManager.getOrNull(uid);
        if (session == null) {
            return PlayerSessionProto.ReturnMainMenuScRsp.newBuilder().setRetcode(RET_NOT_LOGIN).build();
        }

        // 已在主界面：幂等成功
        if (session.getSessionState() == PlayerSessionState.HALL) {
            return PlayerSessionProto.ReturnMainMenuScRsp.newBuilder()
                    .setRetcode(RET_OK)
                    .setSessionState(toProtoState(PlayerSessionState.HALL))
                    .build();
        }

        playSessionCleanupService.leaveCurrentPlay(uid); // 清场景/战斗/Rogue
        if (!PlayerSessionStateMachine.tryTransition(session, PlayerSessionState.HALL)) {
            // 状态机不允许时强制写回主界面，保证「退出回主界面」语义
            session.setSessionState(PlayerSessionState.HALL);
        }
        return PlayerSessionProto.ReturnMainMenuScRsp.newBuilder()
                .setRetcode(RET_OK)
                .setSessionState(toProtoState(PlayerSessionState.HALL))
                .build();
    }

    private EnterTarget resolveEnterTarget(PlayerSessionProto.StartGameCsReq req, GameSession session) {
        if (req.getPlaneId() > 0) {
            int floor = req.getFloorId() > 0 ? req.getFloorId() : DEFAULT_FLOOR_ID;
            return new EnterTarget(req.getPlaneId(), floor, req.getEntryId(),
                    req.getPosX(), req.getPosY(), req.getPosZ());
        }
        PlayerData data = session.getPlayerData();
        PlayerEntity player = data == null ? null : data.getPlayer();
        if (player == null) {
            return new EnterTarget(DEFAULT_PLANE_ID, DEFAULT_FLOOR_ID, DEFAULT_ENTRY_ID, 0f, 0f, 0f);
        }
        int plane = player.getSceneId() > 0 ? player.getSceneId() : DEFAULT_PLANE_ID;
        return new EnterTarget(plane, DEFAULT_FLOOR_ID, DEFAULT_ENTRY_ID,
                toFloat(player.getPosX()), toFloat(player.getPosY()), toFloat(player.getPosZ()));
    }

    private static PlayerSessionProto.StartGameScRsp buildStartOk(int plane, int floor, int entry,
                                                                   float x, float y, float z) {
        return PlayerSessionProto.StartGameScRsp.newBuilder()
                .setRetcode(RET_OK)
                .setSessionState(toProtoState(PlayerSessionState.SCENE))
                .setPlaneId(plane)
                .setFloorId(floor)
                .setEntryId(entry)
                .setPosX(x)
                .setPosY(y)
                .setPosZ(z)
                .build();
    }

    /**
     * 会话状态枚举 → 协议 uint32，与 GetSessionInfo.session_state 一致。
     */
    public static int toProtoState(PlayerSessionState state) {
        if (state == null) {
            return 0;
        }
        return switch (state) {
            case HALL -> 0;
            case SCENE -> 1;
            case BATTLE -> 2;
            case CHALLENGE -> 3;
            case ROGUE -> 4;
            case MATCHING -> 5;
            case STORY -> 6;
            case DIALOGUE -> 7;
        };
    }

    private static float toFloat(BigDecimal v) {
        return v == null ? 0f : v.floatValue();
    }

    private record EnterTarget(int planeId, int floorId, int entryId, float x, float y, float z) {
    }
}
