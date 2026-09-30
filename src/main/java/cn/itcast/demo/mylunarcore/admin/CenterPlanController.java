package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.center.CenterServer;
import cn.itcast.demo.mylunarcore.center.LocalCenterServer;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 供其他游戏节点以 HTTP 查询本机中心服迁移计划（仅 LocalCenter 模式暴露）。
 */
@RestController
@RequestMapping("/internal/center")
@ConditionalOnBean(LocalCenterServer.class)
public class CenterPlanController {

    private final CenterServer centerServer;
    private final LunarCoreProperties properties;

    public CenterPlanController(CenterServer centerServer, LunarCoreProperties properties) {
        this.centerServer = centerServer;
        this.properties = properties;
    }

    @GetMapping("/plan")
    public Map<String, Object> plan(@RequestParam int planeId, @RequestParam int floorId) {
        CenterServer.MigrationPlan plan = centerServer.planMigration(planeId, floorId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("zoneId", plan.zoneId());
        body.put("planeId", plan.planeId());
        body.put("floorId", plan.floorId());
        body.put("nodeId", plan.nodeId());
        String host = plan.nodeHost();
        if (host == null || host.isBlank()) {
            host = properties.getCenter().getAdvertiseHost();
        }
        int port = plan.nodePort() > 0 ? plan.nodePort()
                : (properties.getCenter().getAdvertisePort() > 0
                ? properties.getCenter().getAdvertisePort() : properties.getNettyPort());
        body.put("nodeHost", host == null ? "" : host);
        body.put("nodePort", port);
        return body;
    }
}
