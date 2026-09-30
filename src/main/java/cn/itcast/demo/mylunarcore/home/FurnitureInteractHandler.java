package cn.itcast.demo.mylunarcore.home;

import org.springframework.stereotype.Service;

/**
 * 家具互动：坐椅子/躺床/弹钢琴/拍照打卡，状态位写入 {@link HomePresenceService} 供同家园同步。
 */
@Service
public class FurnitureInteractHandler {

    public record InteractResult(boolean ok, int retcode, HomePresenceService.Presence presence) {
        static InteractResult fail(int retcode) {
            return new InteractResult(false, retcode, null);
        }
    }

    private final HomeBaseService homeBaseService;
    private final HomePresenceService presenceService;

    public FurnitureInteractHandler(HomeBaseService homeBaseService, HomePresenceService presenceService) {
        this.homeBaseService = homeBaseService;
        this.presenceService = presenceService;
    }

    /**
     * @param hostPlayerId 当前所在家园主人；自己家园时等于 actorPlayerId
     */
    public InteractResult interact(int hostPlayerId, int actorPlayerId, int furnitureId,
                                   String action, float x, float y, float z, float rotY, int skinId) {
        if (actorPlayerId <= 0 || hostPlayerId <= 0) {
            return InteractResult.fail(1);
        }
        if (!HomePresenceService.isAllowedAction(action)) {
            return InteractResult.fail(3);
        }
        String act = action.trim().toLowerCase();
        if (!"leave".equals(act) && !"photo".equals(act)) {
            HomeBaseService.HomeState home = homeBaseService.getOrCreate(hostPlayerId);
            boolean found = home.furniture().stream().anyMatch(f -> f.furnitureId() == furnitureId);
            if (!found && furnitureId > 0) {
                // 允许未摆放时的演示互动：仍记录状态，但 furnitureId 保留
                // 严格模式：家具必须存在
                if (home.furniture().isEmpty() && furnitureId > 0) {
                    // 空家园也可拍照/演示
                } else if (!found) {
                    return InteractResult.fail(2);
                }
            }
        }
        String idle = HomePresenceService.toIdleAnim(act);
        int interactFid = "leave".equals(act) || "stand".equals(act) ? 0 : Math.max(0, furnitureId);
        HomePresenceService.Presence presence = new HomePresenceService.Presence(
                actorPlayerId, x, y, z, rotY, Math.max(0, skinId), idle, interactFid);
        presenceService.update(hostPlayerId, presence);
        return new InteractResult(true, 0, presence);
    }
}
