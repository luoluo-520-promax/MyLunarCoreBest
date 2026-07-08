// 游戏全局配置管理器所在包
package cn.itcast.demo.mylunarcore.config;

// Objects.requireNonNull 等非空工具
import java.util.Objects;


/**
 * 饿汉式单例：{@code static final} 在类加载时初始化，由 JVM 保证只初始化一次、线程安全且无显式锁。
 * 具体配置内容由 Spring 在启动后注入 {@link #wire(LunarCoreProperties)}，与进程生命周期一致。
 */
public final class GameConfigurationManager { // final：禁止被继承

    // 类加载时即创建的唯一实例（饿汉式单例）
    private static final GameConfigurationManager INSTANCE = new GameConfigurationManager();

    // volatile：多线程下对 lunarCoreProperties 的读写可见性有保障
    private volatile LunarCoreProperties lunarCoreProperties;

    // 私有构造器：外部不能 new，只能通过 getInstance 获取单例
    private GameConfigurationManager() {
    }

    // 对外提供全局唯一的管理器实例
    public static GameConfigurationManager getInstance() {
        return INSTANCE;
    }

    // 由 Spring 启动组件调用一次，把配置对象挂到单例上（包内可见，外部勿直接调）
    void wire(LunarCoreProperties lunarCoreProperties) {
        // 已注入过则不允许重复注入
        if (this.lunarCoreProperties != null) {
            throw new IllegalStateException("GameConfigurationManager already wired");
        }
        // 拒绝 null，并保存配置引用
        this.lunarCoreProperties = Objects.requireNonNull(lunarCoreProperties);
    }

    // 业务代码读取全局配置；未 wire 前调用会抛异常
    public LunarCoreProperties getLunarCoreProperties() {
        LunarCoreProperties p = lunarCoreProperties; // 局部变量：减少多次读 volatile
        if (p == null) {
            throw new IllegalStateException("GameConfigurationManager not wired yet");
        }
        return p;
    }
}
