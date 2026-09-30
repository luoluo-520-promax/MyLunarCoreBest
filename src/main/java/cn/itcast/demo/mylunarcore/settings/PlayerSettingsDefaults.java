package cn.itcast.demo.mylunarcore.settings; // 玩家设置默认值与校验工具所在包

import cn.itcast.demo.mylunarcore.settings.PlayerSettings.DisplaySettings; // 画面分区类型
import cn.itcast.demo.mylunarcore.settings.PlayerSettings.GameplaySettings; // 游戏细节分区类型
import cn.itcast.demo.mylunarcore.settings.PlayerSettings.KeyBinding; // 单条按键绑定
import cn.itcast.demo.mylunarcore.settings.PlayerSettings.SoundSettings; // 声音分区类型

import java.util.List; // 不可变默认键位列表

/**
 * 玩家设置出厂默认值，以及写入数据库前的合法区间约束（sanitize）。
 * <p>工具类不可实例化；首次建档、重置、脏数据修复都依赖本类。
 */
public final class PlayerSettingsDefaults {

    /**
     * 禁止 new：仅通过静态工厂与 sanitize 使用
     */
    private PlayerSettingsDefaults() {
    } // 私有构造结束

    /**
     * 构造一份完整的出厂设置（四分区均已填充默认值）
     */
    public static PlayerSettings create() {
        PlayerSettings settings = new PlayerSettings(); // 新建聚合对象
        settings.setDisplay(display()); // 填入默认画面
        settings.setSound(sound()); // 填入默认声音
        settings.setKeybinds(keybinds()); // 填入默认键位表
        settings.setGameplay(gameplay()); // 填入默认游戏细节
        return settings; // 返回可直接落库的快照
    } // create 结束

    /**
     * 默认画面：高画质、100% 分辨率、开 VSync、亮度 50、全屏、开抗锯齿
     */
    public static DisplaySettings display() {
        DisplaySettings d = new DisplaySettings(); // 新建画面对象
        d.setGraphicsQuality(2); // 2=高画质，兼顾效果与性能
        d.setResolutionScale(100); // 100%=原生分辨率渲染
        d.setVsync(true); // 默认开垂直同步减轻撕裂
        d.setBrightness(50); // 中等亮度，避免过暗/过亮
        d.setFullscreen(true); // 默认全屏沉浸
        d.setAntiAliasing(true); // 默认开抗锯齿
        return d; // 返回画面默认分区
    } // display 结束

    /**
     * 默认声音：主 80、音乐 70、音效/语音 80、非静音
     */
    public static SoundSettings sound() {
        SoundSettings s = new SoundSettings(); // 新建声音对象
        s.setMasterVolume(80); // 主音量略低于满，留出余量
        s.setMusicVolume(70); // BGM 略低于主音量，避免盖过音效
        s.setSfxVolume(80); // 音效与主音量同档
        s.setVoiceVolume(80); // 语音与主音量同档
        s.setMuted(false); // 默认不静音
        return s; // 返回声音默认分区
    } // sound 结束

    /**
     * 默认键位表：移动 WASD、交互 F、地图 M、背包 B、设置 Escape、技能 Q/E/R 等
     */
    public static List<KeyBinding> keybinds() {
        return List.of( // 不可变列表，调用方若需修改应先拷贝
                new KeyBinding("move_forward", "W"), // 前进
                new KeyBinding("move_back", "S"), // 后退
                new KeyBinding("move_left", "A"), // 左移
                new KeyBinding("move_right", "D"), // 右移
                new KeyBinding("jump", "Space"), // 跳跃
                new KeyBinding("interact", "F"), // NPC/机关交互
                new KeyBinding("open_map", "M"), // 打开地图
                new KeyBinding("open_bag", "B"), // 打开背包
                new KeyBinding("open_settings", "Escape"), // 打开设置界面
                new KeyBinding("skill_1", "Q"), // 技能 1
                new KeyBinding("skill_2", "E"), // 技能 2
                new KeyBinding("ultimate", "R"), // 终结技
                new KeyBinding("camera_lock", "MouseRight") // 镜头锁定（鼠标右键）
        ); // List.of 结束
    } // keybinds 结束

    /**
     * 默认游戏细节：中文、灵敏度 50、关自动战斗、开伤害数字、不跳剧情、开震动、1 倍速
     */
    public static GameplaySettings gameplay() {
        GameplaySettings g = new GameplaySettings(); // 新建细节对象
        g.setLanguage("zh-CN"); // 默认简体中文
        g.setCameraSensitivity(50); // 中等镜头灵敏度
        g.setAutoBattle(false); // 默认需手动操作战斗
        g.setShowDamageNumbers(true); // 默认显示伤害飘字
        g.setSkipStoryCutscene(false); // 默认播放剧情
        g.setVibration(true); // 默认允许震动反馈
        g.setBattleSpeed(1); // 默认 1 倍战斗速度
        return g; // 返回游戏细节默认分区
    } // gameplay 结束

    /**
     * 将设置规整到合法区间：补空分区、夹紧数值、过滤非法键位、限制键位条数，防止客户端脏数据入库
     */
    public static PlayerSettings sanitize(PlayerSettings raw) {
        PlayerSettings settings = raw == null ? create() : raw; // 空入参则整份默认
        DisplaySettings d = settings.getDisplay() == null ? display() : settings.getDisplay(); // 画面缺失则补默认
        d.setGraphicsQuality(clamp(d.getGraphicsQuality(), 0, 3)); // 画质只能 0–3
        d.setResolutionScale(clamp(d.getResolutionScale(), 50, 100)); // 缩放不能低于 50%
        d.setBrightness(clamp(d.getBrightness(), 0, 100)); // 亮度 0–100
        settings.setDisplay(d); // 写回规整后的画面

        SoundSettings s = settings.getSound() == null ? sound() : settings.getSound(); // 声音缺失则补默认
        s.setMasterVolume(clamp(s.getMasterVolume(), 0, 100)); // 主音量夹紧
        s.setMusicVolume(clamp(s.getMusicVolume(), 0, 100)); // 音乐夹紧
        s.setSfxVolume(clamp(s.getSfxVolume(), 0, 100)); // 音效夹紧
        s.setVoiceVolume(clamp(s.getVoiceVolume(), 0, 100)); // 语音夹紧
        settings.setSound(s); // 写回规整后的声音

        if (settings.getKeybinds() == null || settings.getKeybinds().isEmpty()) { // 无键位或空列表
            settings.setKeybinds(keybinds()); // 恢复出厂键位
        } else { // 有自定义键位时做清洗
            settings.setKeybinds(settings.getKeybinds().stream() // 流式过滤
                    .filter(kb -> kb != null // 丢弃 null 元素
                            && kb.getActionId() != null && !kb.getActionId().isBlank() // 动作 ID 必填
                            && kb.getKeyCode() != null && !kb.getKeyCode().isBlank()) // 键位码必填
                    .limit(64) // 最多保留 64 条，防止恶意超大 JSON
                    .toList()); // 收集为新列表
            if (settings.getKeybinds().isEmpty()) { // 过滤后若全部非法
                settings.setKeybinds(keybinds()); // 回退默认键位，避免空绑定无法操作
            } // 空列表回退结束
        } // 键位分支结束

        GameplaySettings g = settings.getGameplay() == null ? gameplay() : settings.getGameplay(); // 细节缺失则补默认
        g.setLanguage(g.getLanguage() == null || g.getLanguage().isBlank() ? "zh-CN" : g.getLanguage().trim()); // 语言空白回退并去空格
        g.setCameraSensitivity(clamp(g.getCameraSensitivity(), 1, 100)); // 灵敏度至少为 1
        g.setBattleSpeed(clamp(g.getBattleSpeed(), 1, 3)); // 倍速仅允许 1–3
        settings.setGameplay(g); // 写回规整后的游戏细节
        return settings; // 返回可安全落库/下发的设置
    } // sanitize 结束

    /**
     * 将整数夹紧到闭区间 [min, max]
     */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value)); // 先上限再下限，得到落在区间内的值
    } // clamp 结束
} // PlayerSettingsDefaults 结束
