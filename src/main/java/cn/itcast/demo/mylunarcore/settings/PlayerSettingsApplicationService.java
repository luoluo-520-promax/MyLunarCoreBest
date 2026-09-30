package cn.itcast.demo.mylunarcore.settings; // 玩家设置应用服务所在包

import org.springframework.stereotype.Service; // 标注为业务服务 Bean

/**
 * 玩家设置应用服务：首次访问建档、按分区合并更新、全量或分区重置。
 * <p>不直接碰协议；由 SettingsNettyService 调用本类后再转 Protobuf。
 */
@Service // 注册为 Spring 单例服务
public class PlayerSettingsApplicationService {

    /**
     * 设置表仓储，负责 UPSERT 与按 uid 查询
     */
    private final PlayerSettingsRepository repository;

    /**
     * 构造注入仓储
     */
    public PlayerSettingsApplicationService(PlayerSettingsRepository repository) {
        this.repository = repository; // 保存持久化依赖
    } // 构造结束

    /**
     * 读取玩家设置；若从未保存过则写入出厂默认并返回该默认快照
     *
     * @param playerId 玩家 uid（与 Netty 会话中的 playerId 一致）
     * @return 已 sanitize 的完整设置
     */
    public PlayerSettings getOrCreate(int playerId) {
        return repository.findByPlayerId(playerId).orElseGet(() -> { // 无行时延迟建档
            PlayerSettings defaults = PlayerSettingsDefaults.create(); // 生成出厂四分区
            repository.upsert(playerId, defaults); // 首次插入，后续跨设备可同步
            return defaults; // 返回刚写入的默认值
        }); // orElseGet 结束
    } // getOrCreate 结束

    /**
     * 合并更新设置。
     * <p>某分区仅在「resetXxx=true」或「incoming 该分区非 null」时覆盖；
     * 否则保留库中原值，支持客户端只改声音、不碰按键等局部提交。
     *
     * @param playerId       玩家 uid
     * @param incoming       协议转换后的入参；可为 null（仅重置场景）
     * @param resetDisplay   true 时画面强制恢复默认，忽略 incoming.display
     * @param resetSound     true 时声音强制恢复默认
     * @param resetKeybinds  true 时按键强制恢复默认
     * @param resetGameplay  true 时游戏细节强制恢复默认
     * @return 合并并落库后的完整设置
     */
    public PlayerSettings update(int playerId,
                                 PlayerSettings incoming,
                                 boolean resetDisplay,
                                 boolean resetSound,
                                 boolean resetKeybinds,
                                 boolean resetGameplay) {
        PlayerSettings current = getOrCreate(playerId); // 先拿到当前（或新建）基线
        if (resetDisplay || (incoming != null && incoming.getDisplay() != null)) { // 需要改画面
            current.setDisplay(resetDisplay
                    ? PlayerSettingsDefaults.display() // 重置：用出厂画面
                    : incoming.getDisplay()); // 更新：用客户端提交的画面
        } // 画面分支结束
        if (resetSound || (incoming != null && incoming.getSound() != null)) { // 需要改声音
            current.setSound(resetSound
                    ? PlayerSettingsDefaults.sound() // 重置声音
                    : incoming.getSound()); // 用提交的声音
        } // 声音分支结束
        if (resetKeybinds || (incoming != null && incoming.getKeybinds() != null && !incoming.getKeybinds().isEmpty())) {
            // 需要改按键：重置，或客户端提交了非空键位表（空列表不覆盖，避免误清空）
            current.setKeybinds(resetKeybinds
                    ? PlayerSettingsDefaults.keybinds() // 恢复默认键位
                    : incoming.getKeybinds()); // 采用客户端键位
        } // 按键分支结束
        if (resetGameplay || (incoming != null && incoming.getGameplay() != null)) { // 需要改细节
            current.setGameplay(resetGameplay
                    ? PlayerSettingsDefaults.gameplay() // 重置细节
                    : incoming.getGameplay()); // 用提交的细节
        } // 细节分支结束
        PlayerSettings sanitized = PlayerSettingsDefaults.sanitize(current); // 合并后再夹紧合法区间
        repository.upsert(playerId, sanitized); // 持久化到 player_settings
        return sanitized; // 返回最终快照供协议回包与推送
    } // update 结束

    /**
     * 重置设置：resetAll=true 时四分区全部出厂；否则按各 resetXxx 标志调用 {@link #update} 做分区重置
     *
     * @param playerId      玩家 uid
     * @param resetAll      true=忽略其它标志，整份恢复默认
     * @param resetDisplay  是否重置画面
     * @param resetSound    是否重置声音
     * @param resetKeybinds 是否重置按键
     * @param resetGameplay 是否重置游戏细节
     * @return 重置并落库后的设置
     */
    public PlayerSettings reset(int playerId,
                                boolean resetAll,
                                boolean resetDisplay,
                                boolean resetSound,
                                boolean resetKeybinds,
                                boolean resetGameplay) {
        if (resetAll) { // 一键恢复全部
            PlayerSettings defaults = PlayerSettingsDefaults.create(); // 全新出厂快照
            repository.upsert(playerId, defaults); // 覆盖库中整行
            return defaults; // 返回默认设置
        } // 全量重置结束
        return update(playerId, null, resetDisplay, resetSound, resetKeybinds, resetGameplay); // 仅按标志重置部分分区
    } // reset 结束
} // PlayerSettingsApplicationService 结束
