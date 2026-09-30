package cn.itcast.demo.mylunarcore.net;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 保证 CmdIds 中每个数值至多绑定一个常量名，避免 Party/角色等号段再次重叠。
 */
class CmdIdUniquenessTest {

    @Test
    void allCmdIdValuesAreUnique() throws IllegalAccessException {
        Map<Integer, List<String>> byValue = new HashMap<>();
        for (Field field : CmdIds.class.getDeclaredFields()) {
            int mod = field.getModifiers();
            if (!Modifier.isStatic(mod) || !Modifier.isFinal(mod) || field.getType() != int.class) {
                continue;
            }
            int value = field.getInt(null);
            byValue.computeIfAbsent(value, k -> new ArrayList<>()).add(field.getName());
        }
        List<String> collisions = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> e : byValue.entrySet()) {
            if (e.getValue().size() > 1) {
                collisions.add(e.getKey() + " -> " + e.getValue());
            }
        }
        assertTrue(collisions.isEmpty(), "CmdId collisions: " + collisions);
    }

    @Test
    void characterRangeNoLongerOverlapsParty() {
        assertTrue(CmdIds.CREATE_CHARACTER_CS_REQ >= 160);
        assertTrue(CmdIds.CREATE_PARTY_CS_REQ < 130);
        if (CmdIds.CREATE_CHARACTER_CS_REQ == CmdIds.CREATE_PARTY_CS_REQ) {
            fail("character/party CREATE still collide");
        }
    }

    @Test
    void guildRangeReservedAndNonOverlapping() {
        assertTrue(CmdIds.CREATE_GUILD_CS_REQ >= 970);
        assertTrue(CmdIds.BUY_GUILD_SHOP_CS_REQ <= 989);
        assertTrue(CmdIds.CREATE_GUILD_CS_REQ > CmdIds.NEWBIE_GUIDE_UPDATE_SC_NOTIFY);
    }

    @Test
    void dailyLoopAndBattlePassRangesAreContiguousAndUnique() {
        assertTrue(CmdIds.GET_BATTLE_PASS_CS_REQ == 990);
        assertTrue(CmdIds.BUY_BATTLE_PASS_PREMIUM_SC_RSP == 995);
        assertTrue(CmdIds.GET_STAMINA_CS_REQ == 996);
        assertTrue(CmdIds.STORY_CHAPTER_UPDATE_SC_NOTIFY == 1008);
        assertTrue(CmdIds.BATTLE_PASS_UPDATE_SC_NOTIFY == 1009);
        // 号段不与公会战重叠
        assertTrue(CmdIds.GET_STAMINA_CS_REQ > CmdIds.GUILD_WAR_RANK_SC_RSP);
    }
}
