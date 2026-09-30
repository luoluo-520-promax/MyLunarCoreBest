package cn.itcast.demo.mylunarcore.net.mapper;

import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.protocol.ActivitySystemProto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * ActivityConfig 与 Protobuf 活动协议之间的映射。
 */
@Component
public class ActivityProtoMapper {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ActivitySystemProto.ActivityConfigEntry toProto(ActivityConfig config) {
        if (config == null) {
            return ActivitySystemProto.ActivityConfigEntry.getDefaultInstance();
        }
        ActivitySystemProto.ActivityConfigEntry.Builder builder = ActivitySystemProto.ActivityConfigEntry.newBuilder()
                .setActivityId(config.getActivityId())
                .setName(nullToEmpty(config.getName()))
                .setActivityType(nullToEmpty(config.getActivityType()))
                .setModuleId(config.getModuleId())
                .setBeginTime(config.getBeginTime())
                .setEndTime(config.getEndTime())
                .setUnlockLevel(config.getUnlockLevel())
                .setDescription(nullToEmpty(config.getDescription()))
                .setGameplay(nullToEmpty(config.getGameplay()))
                .setRules(nullToEmpty(config.getRules()))
                .setRewardMethod(nullToEmpty(config.getRewardMethod()))
                .setShopId(config.getShopId());

        if (config.getConditions() != null) {
            for (ActivityConfig.ActivityCondition condition : config.getConditions()) {
                builder.addConditions(toProto(condition));
            }
        }
        if (config.getStages() != null) {
            for (ActivityConfig.ActivityStage stage : config.getStages()) {
                builder.addStages(toProto(stage));
            }
        }
        if (config.getCostAndLimits() != null) {
            builder.setCostAndLimits(toProto(config.getCostAndLimits()));
        }
        if (config.getRewards() != null) {
            for (ActivityConfig.ActivityReward reward : config.getRewards()) {
                builder.addRewards(toProto(reward));
            }
        }
        if (config.getPointsTokens() != null) {
            for (ActivityConfig.ActivityPointToken token : config.getPointsTokens()) {
                builder.addPointsTokens(toProto(token));
            }
        }
        if (config.getShopProducts() != null) {
            for (ActivityConfig.ShopProduct product : config.getShopProducts()) {
                builder.addShopProducts(toProto(product));
            }
        }
        if (config.getUiResources() != null) {
            builder.setUiResources(toProto(config.getUiResources()));
        }
        if (config.getDisplayText() != null) {
            builder.setDisplayText(toProto(config.getDisplayText()));
        }
        if (config.getExtra() != null && !config.getExtra().isEmpty()) {
            try {
                builder.setExtraJson(objectMapper.writeValueAsString(config.getExtra()));
            } catch (Exception ignored) {
                builder.setExtraJson("{}");
            }
        }
        return builder.build();
    }

    public List<ActivitySystemProto.ActivityConfigEntry> toProtoList(List<ActivityConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return Collections.emptyList();
        }
        return configs.stream().map(this::toProto).toList();
    }

    private ActivitySystemProto.ActivityCondition toProto(ActivityConfig.ActivityCondition condition) {
        return ActivitySystemProto.ActivityCondition.newBuilder()
                .setType(nullToEmpty(condition.getType()))
                .setKey(nullToEmpty(condition.getKey()))
                .setIntValue(condition.getIntValue())
                .setStringValue(nullToEmpty(condition.getStringValue()))
                .build();
    }

    private ActivitySystemProto.ActivityStage toProto(ActivityConfig.ActivityStage stage) {
        ActivitySystemProto.ActivityStage.Builder builder = ActivitySystemProto.ActivityStage.newBuilder()
                .setStageId(stage.getStageId())
                .setName(nullToEmpty(stage.getName()))
                .setDescription(nullToEmpty(stage.getDescription()))
                .setUnlockScore(stage.getUnlockScore());
        if (stage.getRewards() != null) {
            for (ActivityConfig.ActivityReward reward : stage.getRewards()) {
                builder.addRewards(toProto(reward));
            }
        }
        return builder.build();
    }

    private ActivitySystemProto.ActivityCostLimit toProto(ActivityConfig.ActivityCostLimit limit) {
        return ActivitySystemProto.ActivityCostLimit.newBuilder()
                .setCostItemId(limit.getCostItemId())
                .setCostCount(limit.getCostCount())
                .setDailyLimit(limit.getDailyLimit())
                .setTotalLimit(limit.getTotalLimit())
                .setCooldownSeconds(limit.getCooldownSeconds())
                .build();
    }

    private ActivitySystemProto.ActivityReward toProto(ActivityConfig.ActivityReward reward) {
        return ActivitySystemProto.ActivityReward.newBuilder()
                .setItemId(reward.getItemId())
                .setCount(reward.getCount())
                .setType(nullToEmpty(reward.getType()))
                .setGrantTiming(nullToEmpty(reward.getGrantTiming()))
                .build();
    }

    private ActivitySystemProto.ActivityPointToken toProto(ActivityConfig.ActivityPointToken token) {
        return ActivitySystemProto.ActivityPointToken.newBuilder()
                .setTokenId(token.getTokenId())
                .setName(nullToEmpty(token.getName()))
                .setMaxStack(token.getMaxStack())
                .setIconPath(nullToEmpty(token.getIconPath()))
                .build();
    }

    private ActivitySystemProto.ShopProduct toProto(ActivityConfig.ShopProduct product) {
        return ActivitySystemProto.ShopProduct.newBuilder()
                .setProductId(product.getProductId())
                .setItemId(product.getItemId())
                .setPrice(product.getPrice())
                .setCurrencyId(product.getCurrencyId())
                .setDailyLimit(product.getDailyLimit())
                .setTotalLimit(product.getTotalLimit())
                .build();
    }

    private ActivitySystemProto.ActivityUiResources toProto(ActivityConfig.ActivityUiResources ui) {
        return ActivitySystemProto.ActivityUiResources.newBuilder()
                .setBannerImage(nullToEmpty(ui.getBannerImage()))
                .setBackgroundImage(nullToEmpty(ui.getBackgroundImage()))
                .setIconImage(nullToEmpty(ui.getIconImage()))
                .setPrefabPath(nullToEmpty(ui.getPrefabPath()))
                .setThemeColor(nullToEmpty(ui.getThemeColor()))
                .build();
    }

    private ActivitySystemProto.ActivityDisplayText toProto(ActivityConfig.ActivityDisplayText text) {
        return ActivitySystemProto.ActivityDisplayText.newBuilder()
                .setTitle(nullToEmpty(text.getTitle()))
                .setSubtitle(nullToEmpty(text.getSubtitle()))
                .setBannerText(nullToEmpty(text.getBannerText()))
                .setTabLabel(nullToEmpty(text.getTabLabel()))
                .setHelpText(nullToEmpty(text.getHelpText()))
                .build();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
