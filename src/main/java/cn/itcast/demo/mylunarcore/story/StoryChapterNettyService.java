package cn.itcast.demo.mylunarcore.story;

import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.DailyLoopSystemProto;
import io.netty.channel.Channel;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 主线章节协议适配（CmdId 1006–1008）。
 */
@Service
public class StoryChapterNettyService {

    private final StoryChapterService storyChapterService;
    private final PlayerContextResolver playerContextResolver;

    public StoryChapterNettyService(StoryChapterService storyChapterService,
                                    PlayerContextResolver playerContextResolver) {
        this.storyChapterService = storyChapterService;
        this.playerContextResolver = playerContextResolver;
    }

    public DailyLoopSystemProto.GetStoryChapterScRsp handleGet(Channel channel) {
        int playerId = playerContextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return DailyLoopSystemProto.GetStoryChapterScRsp.newBuilder().setRetcode(1).build();
        }
        List<StoryChapterService.ChapterView> views = storyChapterService.list(playerId);
        DailyLoopSystemProto.GetStoryChapterScRsp.Builder rsp =
                DailyLoopSystemProto.GetStoryChapterScRsp.newBuilder().setRetcode(0);
        int current = 0;
        for (StoryChapterService.ChapterView v : views) {
            if (current == 0) {
                current = v.currentChapterId();
            }
            rsp.addChapters(toEntry(v));
        }
        return rsp.setCurrentChapterId(current).build();
    }

    public DailyLoopSystemProto.StoryChapterUpdateScNotify buildNotify(
            StoryChapterService.ChapterView view, int advancedFrom) {
        return DailyLoopSystemProto.StoryChapterUpdateScNotify.newBuilder()
                .setCurrentChapterId(view.currentChapterId())
                .setAdvancedFrom(advancedFrom)
                .setChapter(toEntry(view))
                .build();
    }

    private static DailyLoopSystemProto.StoryChapterEntry toEntry(StoryChapterService.ChapterView v) {
        return DailyLoopSystemProto.StoryChapterEntry.newBuilder()
                .setChapterId(v.chapterId())
                .setTitle(v.title() == null ? "" : v.title())
                .setCurrentChapterId(v.currentChapterId())
                .setUnlocked(v.unlocked())
                .setUnlockPlaneId(v.unlockPlaneId())
                .setUnlockFloorId(v.unlockFloorId())
                .build();
    }
}
