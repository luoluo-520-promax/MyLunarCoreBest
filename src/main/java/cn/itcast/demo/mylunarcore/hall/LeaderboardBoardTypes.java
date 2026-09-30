package cn.itcast.demo.mylunarcore.hall;

/**
 * 可扩展排行榜模板：boardType 与赛季/活动后缀组合为 Redis key。
 */
public final class LeaderboardBoardTypes {

    private LeaderboardBoardTypes() {
    }

    public static final int POWER = 1;
    public static final int ABYSS_FLOOR = 2;
    public static final int ACTIVITY_SCORE = 3;
    public static final int GUILD_WAR = 4;
    public static final int ARENA = 5;

    /** 全服榜 key 片段；seasonOrActivity 空则仅用 boardType。 */
    public static String boardKey(int boardType, String seasonOrActivity) {
        if (seasonOrActivity == null || seasonOrActivity.isBlank()) {
            return Integer.toString(boardType);
        }
        return boardType + ":" + seasonOrActivity.trim();
    }

    public static int parseBoardType(String boardKey) {
        if (boardKey == null || boardKey.isBlank()) {
            return 0;
        }
        int colon = boardKey.indexOf(':');
        String head = colon < 0 ? boardKey : boardKey.substring(0, colon);
        try {
            return Integer.parseInt(head);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
