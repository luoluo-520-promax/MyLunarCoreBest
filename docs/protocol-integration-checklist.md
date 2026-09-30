# 协议联调清单（公会 / 对话·过场 / 家园 / 角色号段）

与客户端约定：逐条核对 CmdId、Protobuf 消息体与 Handler 注册；缺项补齐后纳入集成测试。

权威定义：`CmdIds.java`、`src/main/proto/*.proto`、`PacketCommandRegistry`。

## 1. 公会基础 / 公会战（970–989）

| CmdId | 名称 | Proto | 服务端 Handler | 客户端联调 | 集成测试 |
|------:|------|-------|----------------|:----------:|:--------:|
| 970/971 | CREATE_GUILD | guild.proto | GuildNettyService | ☐ | ☐ |
| 972/973 | JOIN_GUILD | guild.proto | GuildNettyService | ☐ | ☐ |
| 974/975 | LEAVE_GUILD | guild.proto | GuildNettyService | ☐ | ☐ |
| 976/977 | GET_GUILD_INFO | guild.proto | GuildNettyService | ☐ | ☐ |
| 978/979 | GUILD_DONATE | guild.proto | GuildNettyService | ☐ | ☐ |
| 980/981 | GET_GUILD_TECH | guild.proto | GuildNettyService | ☐ | ☐ |
| 982/983 | UPGRADE_GUILD_TECH | guild.proto | GuildNettyService | ☐ | ☐ |
| 984/985 | GUILD_WAR_ENTER / SETTLE | guild.proto | GuildWar* | ☐ | ☐ |
| 986/987 | GUILD_WAR_SCORE | guild.proto | GuildWar* | ☐ | ☐ |
| 988/989 | GUILD_WAR_RANK | guild.proto | GuildWar* | ☐ | ☐ |

能力位：`ClientFeatureFlags.GUILD` / `GUILD_WAR`（登录 `supported_features` 交集后启用）。

## 2. 对话树 / 过场（860–868）

| CmdId | 名称 | Proto | 服务端 | 客户端 | 测试 |
|------:|------|-------|--------|:------:|:----:|
| 860/861 | DIALOGUE_START | dialogue_cutscene.proto | DialogueCutsceneNettyService | ☐ | ☐ |
| 862/863 | DIALOGUE_CHOICE | dialogue_cutscene.proto | 同上 | ☐ | ☐ |
| 864/865 | CUTSCENE_PLAY | dialogue_cutscene.proto | 同上 | ☐ | ☐ |
| 866/867 | CUTSCENE_SKIP | dialogue_cutscene.proto | 同上 | ☐ | ☐ |
| 868 | DIALOGUE_PROGRESS_NOTIFY | dialogue_cutscene.proto | 同上 | ☐ | ☐ |

能力位：`DIALOGUE_TREE`。分支结果见 `DialogueProgressService`（flags / choiceHistory）。

## 3. 家园（850–857）

| CmdId | 名称 | Proto | 服务端 | 客户端 | 测试 |
|------:|------|-------|--------|:------:|:----:|
| 850/851 | GET_HOME_INFO | home.proto | HomeNettyService | ☐ | ☐ |
| 852/853 | UPGRADE_HOME_FACILITY | home.proto | 同上 | ☐ | ☐ |
| 854/855 | PLACE_HOME_FURNITURE | home.proto | 同上 | ☐ | ☐ |
| 856/857 | HOME_VISIT | home.proto | 同上 | ☐ | ☐ |

能力位：`HOME`。社交扩展：点赞 / 繁荣度排行（`HomeSocialService`）。

## 4. 角色号段迁移（160–173，原 120–129）

| CmdId | 名称 | 说明 |
|------:|------|------|
| 160–173 | CREATE_CHARACTER … EQUIP_SKIN | wire_version≥2 唯一合法号段 |
| 120–129 | Party | **勿**再作角色命令；旧客户端须升级或走兼容层 |

详见 [protocol-migration-guide.md](./protocol-migration-guide.md)。

## 5. 联调完成定义（DoD）

1. 请求/响应/Notify 字段与客户端 DTO 一致（含 retcode）。
2. 未声明能力位时服务端不推送对应玩法入口。
3. `CmdIdUniquenessTest` + 模块 Flow 测试通过。
4. CI：`buf lint` + `buf breaking` + wire_version 守卫通过。
