package cn.itcast.demo.mylunarcore.net;

import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协议能力握手：客户端上报 supported_cmd_versions，服务端返回 enabled_cmd_ids；
 * 不支持的命令返回 {@link CmdIds#RET_UNSUPPORTED_CMD}。
 */
@Service
public class ProtocolCompatService {

    public record CapabilityResult(boolean compatible, int serverWireVersion, int minRequiredWireVersion,
                                   List<Integer> enabledCmdIds, String message) {}

    private final Map<Long, Set<Integer>> enabledByUid = new ConcurrentHashMap<>();
    private final List<Integer> allServerCmdIds;

    public ProtocolCompatService() {
        this.allServerCmdIds = List.copyOf(scanCmdIds());
    }

    /** 当前服务端主协议版本（与 {@link CmdIds#PROTOCOL_WIRE_VERSION} 对齐）。 */
    public int currentWireVersion() {
        return CmdIds.PROTOCOL_WIRE_VERSION;
    }

    /**
     * @return true 表示可继续业务；false 表示应拒绝并提示升级客户端。
     */
    public boolean isCompatible(int clientWireVersion) {
        // v1 客户端仍使用角色 120–129，与 Party 冲突，强制升级
        return clientWireVersion >= 2;
    }

    /**
     * 登录后能力协商：clientMin/Max 为客户端声明的 Cmd 版本范围（通常等于 wire version）。
     */
    public CapabilityResult negotiate(long uid, int clientWireVersion, int supportedCmdMin, int supportedCmdMax) {
        int server = currentWireVersion();
        if (!isCompatible(clientWireVersion)) {
            return new CapabilityResult(false, server, 2, List.of(),
                    "client wire too old; min_required_wire_version=2");
        }
        int min = Math.max(0, supportedCmdMin);
        int max = supportedCmdMax > 0 ? supportedCmdMax : Integer.MAX_VALUE;
        List<Integer> enabled = new ArrayList<>();
        for (Integer id : allServerCmdIds) {
            // 简单策略：wire < 4 时屏蔽 v4 新号段 1109+
            if (clientWireVersion < 4 && id >= 1109) {
                continue;
            }
            if (id >= min && id <= max) {
                enabled.add(id);
            }
        }
        enabledByUid.put(uid, new LinkedHashSet<>(enabled));
        return new CapabilityResult(true, server, 2, List.copyOf(enabled), "ok");
    }

    public boolean isCmdEnabled(long uid, int cmdId) {
        Set<Integer> set = enabledByUid.get(uid);
        if (set == null) {
            // 未握手：仅允许基础会话命令
            return cmdId < 1109;
        }
        return set.contains(cmdId);
    }

    public int unsupportedRetcode() {
        return 120; // UNSUPPORTED_CMD，与 CmdId 号段独立的业务 retcode
    }

    /**
     * 历史号段 → 现行号段（仅文档/调试；运行时以 CmdIds 为准）。
     */
    public Map<Integer, Integer> legacyCharacterCmdRemap() {
        Map<Integer, Integer> map = new java.util.HashMap<>();
        map.put(120, CmdIds.CREATE_CHARACTER_CS_REQ);
        map.put(121, CmdIds.CREATE_CHARACTER_SC_RSP);
        map.put(124, CmdIds.PROMOTE_AVATAR_CS_REQ);
        map.put(125, CmdIds.PROMOTE_AVATAR_SC_RSP);
        map.put(126, CmdIds.GET_AVATAR_ATTRIBUTES_CS_REQ);
        map.put(127, CmdIds.GET_AVATAR_ATTRIBUTES_SC_RSP);
        map.put(128, CmdIds.UPGRADE_TALENT_CS_REQ);
        map.put(129, CmdIds.UPGRADE_TALENT_SC_RSP);
        map.put(130, CmdIds.GET_TALENT_LIST_CS_REQ);
        map.put(131, CmdIds.GET_TALENT_LIST_SC_RSP);
        map.put(132, CmdIds.GET_SKIN_WARDROBE_CS_REQ);
        map.put(133, CmdIds.GET_SKIN_WARDROBE_SC_RSP);
        map.put(134, CmdIds.EQUIP_SKIN_CS_REQ);
        map.put(135, CmdIds.EQUIP_SKIN_SC_RSP);
        return Map.copyOf(map);
    }

    private static List<Integer> scanCmdIds() {
        List<Integer> ids = new ArrayList<>();
        for (Field field : CmdIds.class.getDeclaredFields()) {
            int mod = field.getModifiers();
            if (!Modifier.isStatic(mod) || !Modifier.isFinal(mod) || field.getType() != int.class) {
                continue;
            }
            if (field.getName().startsWith("RET_")) {
                continue;
            }
            try {
                ids.add(field.getInt(null));
            } catch (IllegalAccessException ignored) {
            }
        }
        ids.sort(Integer::compareTo);
        return ids;
    }
}
