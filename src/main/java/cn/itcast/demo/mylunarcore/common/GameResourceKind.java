// 静态资源大类枚举（工厂方法模式扩展点）
package cn.itcast.demo.mylunarcore.common;

/**
 * 游戏静态资源种类（工厂方法模式扩展点）。
 */
public enum GameResourceKind {

    /** 表格类 CSV 等，由 {@link TabularStaticResource} 承载 */
    TABULAR // 当前唯一已实现的资源种类
}
