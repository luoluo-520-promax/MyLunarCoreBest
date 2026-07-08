// 可被游戏主循环周期性驱动的接口

// 业务模块
package cn.itcast.demo.mylunarcore.common;

/**

 * 玩家运行时、场景或系统等对象：由游戏循环定时触发 {@link #onTick(long, long)}。

 */

@FunctionalInterface // 单一抽象方法的函数式接口

public interface Tickable {

    /**

     * @param nowMillis   当前服务器 UTC 毫秒时间戳

     * @param deltaMillis 与上一 Tick 的时间间隔（毫秒）

     */

    void onTick(long nowMillis, long deltaMillis);

}

