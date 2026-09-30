package cn.itcast.demo.mylunarcore.tools;

// 公会战相关 CmdId 常量（匹配/战报/排行等）
import cn.itcast.demo.mylunarcore.net.CmdIds;
// Protobuf 生成的公会系统消息类型
import cn.itcast.demo.mylunarcore.protocol.GuildSystemProto;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公会战 Mock 客户端工具：构造 CmdId 984–989 一带的样本请求体（Protobuf 字节），
 * 并可输出 Base64 便于 HTTP/抓包联调与集成测试粘贴。
 * <p>
 * 本类无状态、不可实例化；仅生成报文，不发起真实网络连接。
 */
public final class GuildWarMockClient {

    /** 工具类禁止 new。 */
    private GuildWarMockClient() {
    }

    /**
     * 构造空的公会战匹配请求 {@code GuildWarMatchCsReq} 的序列化字节。
     * 当前 proto 无必填字段，空 builder 即可触发服务端匹配流程。
     */
    public static byte[] matchRequest() {
        return GuildSystemProto.GuildWarMatchCsReq.newBuilder().build().toByteArray();
    }

    /**
     * 构造战报上报请求：携带对局 ID 与双方分数，供服务端结算/校验。
     *
     * @param matchId  对局 ID
     * @param scoreSelf 己方得分
     * @param scoreOpp  对方得分
     */
    public static byte[] reportRequest(long matchId, int scoreSelf, int scoreOpp) {
        return GuildSystemProto.GuildWarReportCsReq.newBuilder()
                .setMatchId(matchId) // 对局主键，与匹配结果一致
                .setScoreSelf(scoreSelf) // 本方得分
                .setScoreOpponent(scoreOpp) // 敌方得分
                .build()
                .toByteArray();
    }

    /**
     * 构造赛季排行查询：指定赛季与 TopN 条数。
     *
     * @param seasonId 赛季 ID
     * @param limit    请求的前 N 名（写入 proto 字段 topN）
     */
    public static byte[] rankRequest(long seasonId, int limit) {
        return GuildSystemProto.GuildWarRankCsReq.newBuilder()
                .setSeasonId(seasonId)
                .setTopN(limit)
                .build()
                .toByteArray();
    }

    /**
     * 生成联调样本字典：键为 {@code "cmdId:逻辑名"}，值为对应请求体的 Base64。
     * 战报样本固定分数 10:3；排行样本 topN=20。
     *
     * @param matchId  填入战报的对局 ID
     * @param seasonId 填入排行查询的赛季 ID
     */
    public static Map<String, String> samplePackets(long matchId, long seasonId) {
        Map<String, String> out = new LinkedHashMap<>(); // 保持插入顺序便于对照文档
        out.put(CmdIds.GUILD_WAR_MATCH_CS_REQ + ":GUILD_WAR_MATCH",
                Base64.getEncoder().encodeToString(matchRequest()));
        out.put(CmdIds.GUILD_WAR_REPORT_CS_REQ + ":GUILD_WAR_REPORT",
                Base64.getEncoder().encodeToString(reportRequest(matchId, 10, 3)));
        out.put(CmdIds.GUILD_WAR_RANK_CS_REQ + ":GUILD_WAR_RANK",
                Base64.getEncoder().encodeToString(rankRequest(seasonId, 20)));
        return out;
    }

    /**
     * 命令行入口：打印 matchId=1001、seasonId=1 的样本 Base64 行。
     */
    public static void main(String[] args) {
        samplePackets(1001L, 1L).forEach((k, v) -> System.out.println(k + " => " + v));
    }
}
