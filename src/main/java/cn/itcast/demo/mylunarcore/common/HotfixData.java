// 客户端热修复 JSON 映射实体
package cn.itcast.demo.mylunarcore.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * 客户端资源基址、热修复版本等可热更新参数（由 JSON 加载，不含业务代码）。
 */
@Setter
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class HotfixData {

    /** 客户端静态资源下载根 URL */
    private String clientResourceBaseUrl = "";

    /** 热修复包版本号（展示/比对用） */
    private String hotfixVersion = "0.0.0";

    /** 递增补丁序号 */
    private long patchVersion;

    /** @return 全默认值的占位对象 */
    public static HotfixData empty() {
        return new HotfixData();
    }
}
