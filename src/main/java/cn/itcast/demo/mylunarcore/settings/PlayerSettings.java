package cn.itcast.demo.mylunarcore.settings; // 玩家游戏设置领域模型所在包

import com.fasterxml.jackson.annotation.JsonIgnoreProperties; // 反序列化时忽略未知字段，兼容热更扩字段
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList; // 可变列表，用于拷贝按键绑定避免外部修改污染
import java.util.List; // 按键绑定集合类型

/**
 * 玩家游戏设置聚合根：画面、声音、按键、游戏细节四个分区。
 * <p>对应表 player_settings 的四列 JSON，也作为协议 PlayerSettingsSnapshot 的内存形态。
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true) // JSON 多出字段不报错，便于前后端版本不完全一致时仍能加载
public class PlayerSettings {

    // 允许 null，供 SettingsNettyService.fromProto 区分「未携带」与「携带默认值」
    // setDisplay 结束
    // 直接返回引用，调用方勿长期缓存后跨请求修改
    // getDisplay 结束
    /**
     * 画面分区：画质档位、分辨率缩放、亮度、全屏、垂直同步、抗锯齿
     * -- SETTER --
     *  写入画面分区；传 null 表示部分更新时跳过该分区（不覆盖库中原画面设置）
     * -- GETTER --
     *  读取画面分区；可能为 null，表示协议层「本分区未携带、更新时保留原值」


     */
    @Setter
    private DisplaySettings display = PlayerSettingsDefaults.display();
    // 保留 null 语义，避免误写成默认音量覆盖用户配置
    // setSound 结束
    // 返回当前声音设置对象
    // getSound 结束
    /**
     * 声音分区：主音量、音乐、音效、语音与全局静音
     * -- SETTER --
     *  写入声音分区；null 表示更新请求未携带本分区
     * -- GETTER --
     *  读取声音分区；可能为 null，语义同画面分区


     */
    @Setter
    private SoundSettings sound = PlayerSettingsDefaults.sound();
    // 返回列表引用；落库前会再拷贝序列化
    // getKeybinds 结束
    /**
     * 按键绑定列表：每项为「动作标识 + 键位码」，客户端按 actionId 匹配操作
     * -- GETTER --
     *  读取按键绑定列表；可能为 null 或空，落盘前会由 sanitize 补默认

     */
    private List<KeyBinding> keybinds = new ArrayList<>(PlayerSettingsDefaults.keybinds());
    // 允许 null，供分区合并逻辑判断
    // setGameplay 结束
    // 返回游戏细节设置
    // getGameplay 结束
    /**
     * 游戏细节分区：语言、镜头灵敏度、自动战斗、伤害数字、剧情跳过、震动、战斗倍速
     * -- SETTER --
     *  写入游戏细节分区；null 表示更新请求未携带本分区
     * -- GETTER --
     *  读取游戏细节分区；可能为 null


     */
    @Setter
    private GameplaySettings gameplay = PlayerSettingsDefaults.gameplay();

    /**
     * 写入按键列表；null 表示未携带；非 null 时拷贝一份，避免调用方后续改动影响本对象
     */
    public void setKeybinds(List<KeyBinding> keybinds) {
        this.keybinds = keybinds == null ? null : new ArrayList<>(keybinds); // null 保留「未更新」语义，否则防御性拷贝
    } // setKeybinds 结束

    /**
     * 画面相关选项，序列化到 player_settings.display_json
     */
    @Setter
    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true) // 兼容后续新增画面字段
    public static class DisplaySettings {
        // 返回 0–3 档位（落盘前会 clamp）
        // getGraphicsQuality 结束
        // 先原样写入，统一在 Defaults.sanitize 校验
        // setGraphicsQuality 结束
        /**
         * 画质档位：0=低，1=中，2=高，3=极致；默认 2（高）
         * -- GETTER --
         *  获取画质档位
         * -- SETTER --
         *  设置画质档位（越界值在 sanitize 时夹紧到 0–3）


         */
        private int graphicsQuality = 2;
        // 返回当前缩放比例
        // getResolutionScale 结束
        // 写入原始值
        // setResolutionScale 结束
        /**
         * 渲染分辨率相对原生分辨率的百分比，合法区间 50–100，默认 100
         * -- GETTER --
         *  获取分辨率缩放百分比
         * -- SETTER --
         *  设置分辨率缩放（sanitize 夹紧到 50–100）


         */
        private int resolutionScale = 100;
        // true 表示开启 VSync
        // isVsync 结束
        // 直接赋值布尔开关
        // setVsync 结束
        /**
         * 是否开启垂直同步，减轻画面撕裂；默认开启
         * -- GETTER --
         *  是否开启垂直同步
         * -- SETTER --
         *  设置垂直同步开关


         */
        private boolean vsync = true;
        // 返回 0–100 亮度
        // getBrightness 结束
        // 写入原始亮度
        // setBrightness 结束
        /**
         * 画面亮度百分比 0–100，默认 50
         * -- GETTER --
         *  获取亮度百分比
         * -- SETTER --
         *  设置亮度（sanitize 夹紧到 0–100）


         */
        private int brightness = 50;
        // true=全屏，false=窗口
        // isFullscreen 结束
        // 写入显示模式
        // setFullscreen 结束
        /**
         * 是否全屏显示；默认全屏
         * -- GETTER --
         *  是否全屏
         * -- SETTER --
         *  设置全屏/窗口模式


         */
        private boolean fullscreen = true;
        // true 表示开启 AA
        // isAntiAliasing 结束
        // 写入抗锯齿选项
        // setAntiAliasing 结束
        /**
         * 是否开启抗锯齿；默认开启
         * -- GETTER --
         *  是否开启抗锯齿
         * -- SETTER --
         *  设置抗锯齿开关


         */
        private boolean antiAliasing = true;

    } // DisplaySettings 结束

    /**
     * 声音相关选项，序列化到 player_settings.sound_json
     */
    @Setter
    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true) // 兼容后续新增声道字段
    public static class SoundSettings {
        // 返回主音量数值
        // getMasterVolume 结束
        // 写入原始主音量
        // setMasterVolume 结束
        /**
         * 主音量 0–100，作为其它声道的总增益基准，默认 80
         * -- GETTER --
         *  获取主音量
         * -- SETTER --
         *  设置主音量（sanitize 夹紧到 0–100）


         */
        private int masterVolume = 80;
        // 返回 BGM 音量
        // getMusicVolume 结束
        // 写入 BGM 音量
        // setMusicVolume 结束
        /**
         * 背景音乐音量 0–100，默认 70
         * -- GETTER --
         *  获取音乐音量
         * -- SETTER --
         *  设置音乐音量


         */
        private int musicVolume = 70;
        // 返回 SFX 音量
        // getSfxVolume 结束
        // 写入 SFX 音量
        // setSfxVolume 结束
        /**
         * 战斗/UI 等音效音量 0–100，默认 80
         * -- GETTER --
         *  获取音效音量
         * -- SETTER --
         *  设置音效音量


         */
        private int sfxVolume = 80;
        // 返回语音音量
        // getVoiceVolume 结束
        // 写入语音音量
        // setVoiceVolume 结束
        /**
         * 角色语音音量 0–100，默认 80
         * -- GETTER --
         *  获取语音音量
         * -- SETTER --
         *  设置语音音量


         */
        private int voiceVolume = 80;
        // true 表示客户端应静音
        // isMuted 结束
        // 写入静音标志
        // setMuted 结束
        /**
         * 全局静音：为 true 时客户端应忽略各声道数值并静音输出
         * -- GETTER --
         *  是否全局静音
         * -- SETTER --
         *  设置全局静音开关


         */
        private boolean muted;

    } // SoundSettings 结束

    /**
     * 单条按键绑定：逻辑动作 actionId 映射到物理键位 keyCode
     */
    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true) // 兼容后续扩展修饰键等字段
    public static class KeyBinding {
        // 返回动作 ID
        // getActionId 结束
        /**
         * 逻辑动作标识，如 move_forward、open_settings，客户端用此匹配输入系统
         * -- GETTER --
         *  获取逻辑动作标识

         */
        private String actionId = "";
        // 返回键位字符串
        // getKeyCode 结束
        /**
         * 键位码字符串，如 W、Escape、MouseRight，由客户端与输入库约定
         * -- GETTER --
         *  获取键位码

         */
        private String keyCode = "";
        private String deviceType = "pc";
        private int scale = 100;
        private float posX;
        private float posY;

        /**
         * Jackson / JDBC 反序列化用无参构造
         */
        public KeyBinding() {
        } // 无参构造结束

        /**
         * 便捷构造：同时指定动作与键位，空引用转为空串避免 NPE
         */
        public KeyBinding(String actionId, String keyCode) {
            this.actionId = actionId == null ? "" : actionId; // 动作标识空安全
            this.keyCode = keyCode == null ? "" : keyCode; // 键位码空安全
        } // 双参构造结束

        public KeyBinding(String actionId, String keyCode, String deviceType, int scale, float posX, float posY) {
            this(actionId, keyCode);
            this.deviceType = deviceType == null || deviceType.isBlank() ? "pc" : deviceType;
            this.scale = scale <= 0 ? 100 : Math.min(200, Math.max(50, scale));
            this.posX = posX;
            this.posY = posY;
        }

        /**
         * 设置逻辑动作标识；null 转为空串
         */
        public void setActionId(String actionId) {
            this.actionId = actionId == null ? "" : actionId; // 避免库中出现 null 字符串字段
        } // setActionId 结束

        /**
         * 设置键位码；null 转为空串
         */
        public void setKeyCode(String keyCode) {
            this.keyCode = keyCode == null ? "" : keyCode; // 空安全写入
        } // setKeyCode 结束

        public void setDeviceType(String deviceType) {
            this.deviceType = deviceType == null || deviceType.isBlank() ? "pc" : deviceType;
        }

        public void setScale(int scale) {
            this.scale = scale <= 0 ? 100 : Math.min(200, Math.max(50, scale));
        }

        public void setPosX(float posX) {
            this.posX = posX;
        }

        public void setPosY(float posY) {
            this.posY = posY;
        }
    } // KeyBinding 结束

    /**
     * 游戏细节选项，序列化到 player_settings.gameplay_json
     */
    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true) // 兼容后续新增玩法开关
    public static class GameplaySettings {
        // 返回如 zh-CN、en-US
        // getLanguage 结束
        /**
         * 界面与助手文案语言，默认 zh-CN
         * -- GETTER --
         *  获取语言代码

         */
        private String language = "zh-CN";
        // 返回灵敏度数值
        // getCameraSensitivity 结束
        // 写入原始灵敏度
        // setCameraSensitivity 结束
        /**
         * 镜头灵敏度 1–100，默认 50
         * -- GETTER --
         *  获取镜头灵敏度
         * -- SETTER --
         *  设置镜头灵敏度（sanitize 夹紧到 1–100）


         */
        @Setter
        private int cameraSensitivity = 50;
        // true 表示客户端可自动出招
        // isAutoBattle 结束
        // 写入自动战斗标志
        // setAutoBattle 结束
        /**
         * 是否开启自动战斗；默认关闭，需玩家主动操作
         * -- GETTER --
         *  是否自动战斗
         * -- SETTER --
         *  设置自动战斗开关


         */
        @Setter
        private boolean autoBattle;
        // true 表示显示飘字
        // isShowDamageNumbers 结束
        // 写入飘字开关
        // setShowDamageNumbers 结束
        /**
         * 是否显示飘字伤害数字；默认开启
         * -- GETTER --
         *  是否显示伤害数字
         * -- SETTER --
         *  设置伤害数字显示开关


         */
        @Setter
        private boolean showDamageNumbers = true;
        // true 表示跳过 cutscene
        // isSkipStoryCutscene 结束
        // 写入剧情跳过标志
        // setSkipStoryCutscene 结束
        /**
         * 是否跳过剧情演出；默认不跳过
         * -- GETTER --
         *  是否跳过剧情演出
         * -- SETTER --
         *  设置跳过剧情开关


         */
        @Setter
        private boolean skipStoryCutscene;
        // true 表示允许震动
        // isVibration 结束
        // 写入震动标志
        // setVibration 结束
        /**
         * 是否启用手柄/设备震动反馈；默认开启
         * -- GETTER --
         *  是否开启震动
         * -- SETTER --
         *  设置震动开关


         */
        @Setter
        private boolean vibration = true;
        // 返回 1–3 倍速
        // getBattleSpeed 结束
        // 写入原始倍速
        // setBattleSpeed 结束
        /**
         * 战斗播放倍速：1 / 2 / 3，默认 1 倍
         * -- GETTER --
         *  获取战斗倍速
         * -- SETTER --
         *  设置战斗倍速（sanitize 夹紧到 1–3）


         */
        @Setter
        private int battleSpeed = 1;

        /**
         * 设置语言；空或空白时回退 zh-CN，避免客户端传空串导致无文案
         */
        public void setLanguage(String language) {
            this.language = language == null || language.isBlank() ? "zh-CN" : language; // 空值回退中文
        } // setLanguage 结束

    } // GameplaySettings 结束
} // PlayerSettings 结束
