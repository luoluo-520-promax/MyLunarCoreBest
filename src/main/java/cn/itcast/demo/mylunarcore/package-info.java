/**
 * MyLunarCore 游戏服主模块（扁平包结构，便于初学者阅读）：
 * <ul>
 *   <li>{@code config} — 配置与 Spring 装配</li>
 *   <li>{@code model} — 玩家/玩法领域实体与值对象</li>
 *   <li>{@code repo} — 数据库访问（原各模块 *.repo / repository）</li>
 *   <li>{@code net} — Netty/KCP 协议、编解码与命令分发</li>
 *   <li>{@code player} — 会话、登录与数据同步</li>
 *   <li>{@code battle|scene|rogue|gacha|challenge|item} — 各玩法业务与 Netty 服务</li>
 *   <li>{@code common} — 日志、监控、热更新、主循环、活动等基础设施</li>
 *   <li>{@code admin} — 后台 RBAC 与 HTTP 接口</li>
 * </ul>
 */
package cn.itcast.demo.mylunarcore;
