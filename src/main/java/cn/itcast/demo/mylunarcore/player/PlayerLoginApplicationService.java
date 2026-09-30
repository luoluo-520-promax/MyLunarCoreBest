// 登录流程中跨多表写库操作的事务封装
package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.model.AccountEntity;
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

/**
 * 登录应用服务层。
 * <p>
 * 将账号表（account）与玩家表（player）的「最近登录时间、最近登录 IP」更新
 * 封装在同一 {@link Transactional} 边界内，保证审计字段原子一致。
 * 由 {@link PlayerSessionService#completeSuccessfulLogin} 在会话创建前调用。
 */
@Service
public class PlayerLoginApplicationService {

    /** 玩家与账号数据访问层。 */
    private final PlayerDataRepository repository;

    public PlayerLoginApplicationService(PlayerDataRepository repository) {
        this.repository = repository;
    }

    /**
     * 登录成功时原子更新账号与玩家的登录审计字段。
     * <p>
     * updateAccountLogin 内部通常包含：account.last_login_at、account.last_login_ip、
     * player.last_login_at 等字段的 UPDATE，任一失败则整体回滚。
     *
     * @param account     已校验通过的账号实体
     * @param uid         登录的玩家 uid
     * @param now         本次登录时间戳
     * @param lastLoginIp 客户端 IP（来自 Netty Handler 解析的 remoteAddress）
     */
    @Transactional
    public void recordSuccessfulLogin(AccountEntity account, long uid, Timestamp now, String lastLoginIp) {
        repository.updateAccountLogin(account, uid, now, lastLoginIp);
    }
}
