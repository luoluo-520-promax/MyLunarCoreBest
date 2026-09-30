package cn.itcast.demo.mylunarcore.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Map;

/**
 * 客户端表现参数：随配置或协议下发特效/镜头/文案占位符。
 * 对应 data/*.json 中的 {@code client_ui_params} 字段。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientUiParams(
        @JsonProperty("fxId") String fxId,
        @JsonProperty("cameraPreset") String cameraPreset,
        @JsonProperty("textPlaceholders") Map<String, String> textPlaceholders,
        @JsonProperty("sfxId") String sfxId,
        @JsonProperty("timelineId") String timelineId
) {
    public static ClientUiParams empty() {
        return new ClientUiParams("", "", Collections.emptyMap(), "", "");
    }

    public ClientUiParams {
        if (fxId == null) {
            fxId = "";
        }
        if (cameraPreset == null) {
            cameraPreset = "";
        }
        if (textPlaceholders == null) {
            textPlaceholders = Collections.emptyMap();
        }
        if (sfxId == null) {
            sfxId = "";
        }
        if (timelineId == null) {
            timelineId = "";
        }
    }
}
