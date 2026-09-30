package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.repo.GameDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将统一活动配置拆分到排期、卡池、道具、活动详情与 game_data 各配置面。
 */
@Service
public class ActivityImportService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ACTIVITY, ActivityImportService.class);

    public static final String GAME_DATA_KEY_PREFIX = "activity.";

    private final ConfigFileService configFileService;
    private final GameDataRepository gameDataRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ActivityImportService(ConfigFileService configFileService, GameDataRepository gameDataRepository) {
        this.configFileService = configFileService;
        this.gameDataRepository = gameDataRepository;
    }

    public ImportResult importUnified(ActivityConfig config) throws Exception {
        if (config == null || config.getActivityId() <= 0) {
            throw new IllegalArgumentException("activityId is required");
        }

        configFileService.mergeScheduleEntry(scheduleEntry(config));
        configFileService.mergeBannerEntries(bannerEntries(config));
        configFileService.mergeItemRows(itemRows(config));
        configFileService.mergeActivityConfigEntry(config);

        String detailPath = "activities/activity." + config.getActivityId() + ".json";
        configFileService.writeJson(detailPath, config);

        String payloadJson = objectMapper.writeValueAsString(config);
        gameDataRepository.upsert(GAME_DATA_KEY_PREFIX + config.getActivityId(), payloadJson);

        log.info("Imported unified activity: id={}, name={}", config.getActivityId(), config.getName());
        return new ImportResult(config.getActivityId(), detailPath, GAME_DATA_KEY_PREFIX + config.getActivityId());
    }

    private Map<String, Object> scheduleEntry(ActivityConfig config) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("activityId", config.getActivityId());
        entry.put("beginTime", config.getBeginTime());
        entry.put("endTime", config.getEndTime());
        entry.put("moduleId", config.getModuleId());
        return entry;
    }

    private List<Map<String, Object>> bannerEntries(ActivityConfig config) {
        List<Map<String, Object>> banners = new ArrayList<>();
        if (config.getBanners() == null) {
            return banners;
        }
        for (ActivityConfig.ActivityBannerRef banner : config.getBanners()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", banner.getId());
            row.put("gachaType", banner.getGachaType());
            row.put("beginTime", banner.getBeginTime() > 0 ? banner.getBeginTime() : config.getBeginTime());
            row.put("endTime", banner.getEndTime() > 0 ? banner.getEndTime() : config.getEndTime());
            row.put("rateUpItems5", banner.getRateUpItems5());
            row.put("rateUpItems4", banner.getRateUpItems4());
            banners.add(row);
        }
        return banners;
    }

    private List<String[]> itemRows(ActivityConfig config) {
        List<String[]> rows = new ArrayList<>();
        if (config.getItems() == null) {
            return rows;
        }
        for (ActivityConfig.ActivityItemRef item : config.getItems()) {
            rows.add(new String[]{
                    String.valueOf(item.getId()),
                    item.getName() == null ? "" : item.getName(),
                    String.valueOf(item.getStack())
            });
        }
        return rows;
    }

    public record ImportResult(int activityId, String detailFile, String gameDataKey) {
    }
}
