# ai-agent-platform（MPOSTOR 游戏平台 · 后端）

![lang](https://img.shields.io/badge/lang-Java-informational) ![status](https://img.shields.io/badge/status-maintained-brightgreen)


> 工业风 AI 桌游平台后端。配套前端项目：`E:\NET Program\ai-undercover-agent`（纯 HTML/JS，live-server 端口 3000）。
> 项目名与 Java 包名 `com.smxy.agent` 为历史约定，**勿改**。

---

## 一、项目简介

- **后端**：Spring Boot 4.0.6（Java），端口 `8080`
  - WebSocket `/ws/game`：谁是卧底联机 + 4 个新联机游戏（拆弹小队/成语接龙/海龟汤/趣味问答）
  - REST `/api/...`：5 个单机游戏 + 账号体系
  - LLM：智谱（Zhipu）为主，DeepSeek/OpenAI 可切换，失败自动降级
- **前端**：`E:\NET Program\ai-undercover-agent`（无框架纯 JS + 工业终端风 CSS，端口 `3000`）
- **双模式**：每个游戏同时支持**单机模式**（REST）与**联机模式**（WS 真人同房，AI 仅当裁判/主持）

## 二、功能总览

| 游戏 | 联机 | 单机 | 说明 |
|---|---|---|---|
| 谁是卧底 |  WS（一期） |  | 完整发言/投票流程，AI 玩家 |
| 拆弹小队 |  bomb-room |  number-bomb | 轮流剪线逼近密码，剪中密码线拆弹获胜（含干扰线/熔断线阻碍） |
| 成语接龙 |  idiom-room |  idiom-chain | LLM 裁判，2 犯淘汰，房主可跳过 |
| 海龟汤 |  turtle-room |  turtle-soup | 房主掌握汤底手动判答，20 问上限 |
| 趣味问答 |  quiz-room |  quiz | 同题竞技，全员答完揭晓，排名结算 |

**难度系统（v2.1）**：创建房间时选择 简单/普通/困难（`createRoom` data 带 `difficulty`，非法值回落 normal）。
- 谁是卧底：三档词库 —— 困难档为形容词强化的近似词对（如 冰镇西瓜/常温西瓜）
- 拆弹小队：阻碍线密度按难度缩放（easy 无障碍，hard 加密）；单机拆弹同规则
- 成语接龙/海龟汤/趣味问答：LLM 出题提示词按难度调节（大众成语/简单场景/常识题  生僻成语/多重反转/冷知识）

其他能力：

- **全局登录**：用户名+密码注册/登录（前端 `src/pages/login/`），token 24h，WS 联机需认证，战绩按账号隔离
- **语音**：TTS 播报（题目/结果/提示）+ 语音输入（麦克风，Chrome 支持，Firefox 自动隐藏按钮）
- **大厅 5 选项卡**：`src/pages/game-hall/`，按游戏类型分 tab，创建房间自动跳转对应房间页
- **断线重连**：刷新/断线 5 秒内重连可恢复对局（rejoin 快照，不泄题）

## 三、目录结构

```
ai-agent-platform/
├── backend.log               # ← 当前运行日志（见「六、运行日志」）
├── scripts/                  # 冒烟测试脚本（见「五、测试脚本」）
├── pom.xml / mvnw / src
└── src/main/java/com/smxy/agent/
    ├── auth/                 # UserStore / TokenStore / AuthService（账号体系，内存）
    ├── controller/           # AuthController + 6 个游戏 REST 控制器
    ├── websocket/            # GameWebSocketHandler / GameRoomManager / GameFlowManager
    │   └── logic/            # RoomGameLogic 接口 + 4 个联机游戏逻辑 + 注册表
    ├── game/                 # GameLLMHelper + IdiomBank / QuizBank / TurtleBank（本地题库）
    ├── llm/                  # ZhipuService / DeepSeekService / OpenAIService / MockLLMService
    └── ...（application/config/core/memory/tool 等既有包）
```

前端关键目录（`E:\NET Program\ai-undercover-agent`）：

```
src/
├── fun/mpostor.js            # MPOSTOR.auth（登录门禁）/ voice（TTS+语音输入）/ 音效 / 统计 / 成就栏 / 开局部署过渡
├── pages/
│   ├── login/                # 登录/注册页
│   ├── game-hall/            # 大厅（5 选项卡 + 创建/加入房间）
│   ├── game-play/ game-setup/ game-result/   # 卧底联机三页
│   ├── bomb-room/ idiom-room/ turtle-room/ quiz-room/   # 4 个新联机房间页
│   └── number-bomb/ turtle-soup/ quiz/ guess-word/ idiom-chain/   # 5 个单机页
└── index.html / index.js     # 首页（账号名/退出/联机入口）
```

## 四、快速启动

```bash
# 1. 后端（在 E:\NET Program\ai-agent-platform 下）
./mvnw spring-boot:run                     # 端口 8080
# 或带日志落盘（见「六、运行日志」）：
./mvnw spring-boot:run > backend.log 2>&1 &

# 2. 前端（在 E:\NET Program\ai-undercover-agent 下）
live-server --port=3000                    # 浏览器打开 http://localhost:3000

# 3. 重启后端（Windows Git Bash）：
netstat -ano | grep ":8080" | grep LISTEN | awk '{print $NF}' | sort -u   # 查 PID
taskkill //F //PID <pid>
```

**注意**：账号、token、房间全部在**内存**中，后端重启即清空（浏览器 localStorage 里旧的 token 也会失效，需重新登录）。

## 五、测试脚本（scripts/）

三个 Node 冒烟脚本（Node 24+，全局 WebSocket）：

```bash
node scripts/ws-smoke.mjs all       # WS 5 场景冒烟：bomb / idiom / turtle / quiz / undercover(难度)（112 项检查）
node scripts/ws-smoke.mjs bomb      # 按场景跑：bomb|idiom|turtle|quiz|undercover|all
node scripts/bomb-rest-smoke.mjs    # 拆弹 REST 冒烟：阻碍线 + 难度档位（53 项检查）
node scripts/undercover-flow-smoke.mjs  # 卧底浏览器流冒烟：待命快照→待命区开局→解散广播→死房 rejoin（34 项检查）
node scripts/auth-smoke.mjs         # WS auth 门禁 5 项检查
node scripts/rest-check.mjs         # REST 单机 4 游戏回归 + auth 接口 9 项
```

> 输出里的 `Assertion failed: !(handle->flags & UV_HANDLE_CLOSING)` 是 Windows 上 Node 退出时的无害产物，可忽略（`| grep -v "Assertion failed"` 过滤）。

## 六、运行日志

**有日志。** 位置与现状：

| 日志 | 位置 | 说明 |
|---|---|---|
| 后端运行日志 | 根目录 `backend.log` | 最新一次 `spring-boot:run` 的完整 stdout/stderr 重定向 |
| 历史里程碑日志 | 未留存 | 每次重启旧日志会被新日志替换（如 backend-m4.log 等已被覆盖删除） |
| 前端日志 | 无落盘 | 前端为静态页，日志只在浏览器 Console（F12） |

日志内容示例（`backend.log` 中可 grep）：房间创建 `[HOME]`、开局 `[BOMB]/[IDIOM]/[TURTLE]/[QUIZ]/[GAME]` 等标签前缀事件行；LLM 调用与降级记录；Spring 启动日志。（v2.1 起日志已全部去除 emoji 前缀，改用文本标签。）

生成新日志：

```bash
./mvnw spring-boot:run > backend-<标记>.log 2>&1 &
tail -f backend-<标记>.log
```

## 七、账号与数据存储

- **注册规则**：用户名 2-20 字符，密码 4-32 字符；密码 **SHA-256+盐** 哈希存储，不存明文
- **登录**：成功返回 token（24h 有效），前端存 `localStorage.mpostor_account = {token, username}`
- **存储位置**：
  - 后端：全部内存（`auth/UserStore`、`auth/TokenStore`、`GameRoomManager` 房间/对局状态）——**重启即清空**
  - 前端：localStorage（登录凭证、战绩 `mpostor_stats_<用户名>`、断线重连数据 `bombGameData` 等）
  - 配置：`src/main/resources/application.yml`（含 LLM API key，**勿提交公开仓库、勿在日志中打印**）
- **无数据库**：目前刻意保持轻量；如需账号/战绩跨重启保留，可给 UserStore/TokenStore 加持久层（如 H2/SQLite），前端协议不用动

## 八、WS 协议速览

- Envelope：客户端  `{type, playerId, data}`；服务端单播 `{type, message, data}`；房间广播为扁平 map
- **认证**：连接后先发 `{type:"auth", data:{token}}`  `authSuccess{username}` / `error`
  - 需认证：createRoom/joinRoom/startGame/rejoinRoom/leaveRoom/closeRoom/resetRoom/kickPlayer 及全部游戏动作
  - 公开：join、listRooms、heartbeat
- **创建房间**：createRoom data 带 `gameType`（undercover|bomb|idiom|turtle|quiz，缺省 undercover）+ `difficulty`（easy|normal|hard，缺省 normal，非法回落 normal）；roomList/roomCreated 回显 difficulty
- **各游戏消息**（类型名）：
  - bomb：`bombGuess`；广播 `bombStarted{difficulty, decoyCount, fuseCount}` / `bombUpdate{SAFE|DECOY|FUSE|DEFUSED|BOOM|LEFT}` / `gameEnded`（阻碍线只下发数量，位置永不泄漏）
  - idiom：`idiomPlay` / `idiomSkip`；广播 `idiomStarted{difficulty}` / `idiomUpdate{valid, strikes, eliminated}` / `gameEnded`
  - turtle：`turtleAsk` / `turtleAnswer`（仅房主）/ `turtleGuess` / `turtleReveal`；广播 `turtleStarted{difficulty}` + 房主独播 `turtleHostSecret{answer,keywords}` / `turtleAsked` / `turtleAnswered` / `turtleGuessResult` / `turtleRevealed` / `gameEnded`
  - quiz：`quizAnswer` / `quizNext`（房主）；广播 `quizStarted{difficulty}` / `quizQuestion`（**无答案字段**）/ `quizAnswered` / `quizReveal{correctIndex,explanation,results,scores}` / `gameEnded{ranking}`
  - undercover：`gameStarted` 附带 `difficulty`；对局页有**待命区**（status=waiting 时房主可见「开始游戏/解散房间」按钮，非房主见等待提示），房主点开始后全员**就地**收 `gameStarted` 进入对局（大厅停留者仍走跳转路径）
  - `closeRoom`：先向房内全员广播 `roomClosed`，再删除房间并刷新列表（先删后播会被静默丢弃，顺序有坑）
- **rejoinRoom**：快照含 base（roomId/players/status/gameType/difficulty/displayNames/host/name/maxPlayers）+ 按游戏类型合并对局状态；**题目答案/炸弹数字/阻碍线位置/汤底永不进快照与广播**（防泄题）
- **防冒充**：playerId 首次绑定账号后，后续操作必须来自同账号会话
- **协议兼容约束**：卧底（undercover）消息字段名与一期完全一致，未改动

## 九、REST 接口速览

- Auth：`POST /api/auth/register` / `/login`（返回 `{token, username}`）/ `/me` / `/logout`
- 单机游戏：`/api/game/undercover/*`（创建/测试）、`/api/game/bomb/*`、`/api/game/idiom/*`、`/api/game/turtle/*`、`/api/game/quiz/*`、`/api/game/guess/*`
- 统一响应 `Result<T>`：`code` 0=成功 / 400=参数错 / 401=未登录，`message` + `data`

## 十、开发日志（更新记录）

### 一期

- 谁是卧底 WS 联机（GameFlowManager 发言/投票/结算全流程）+ 5 个单机 REST 游戏
- 工业终端风 UI、AI 玩家、音效/成就等趣味组件（mpostor.js）

### 二期

**后端（里程碑 M1-M6，每步可编译，卧底链路全程兼容）**

| 里程碑 | 内容 |
|---|---|
| M1 | RoomData 加 `gameType` + `extra`（对局状态，**绝不整体序列化**）；roomInfo 加 gameType/displayNames |
| M2 | 题库抽取：IdiomBank / QuizBank / TurtleBank（纯重构，REST 行为不变） |
| M3 | 账号体系：UserStore / TokenStore / AuthService / AuthController；WS `auth` 消息 + 门禁 + 防冒充 |
| M4 | RoomGameLogic 接口 + 4 个联机逻辑类（Bomb/Idiom/Turtle/Quiz）+ GameLogicRegistry |
| M5 | handler 分发接线、9 个新消息 case、rejoinRoom 快照按类型扩展、离场钩子、断线 5s 宽限重连 |
| M6 | 4 处 AI 提示词强化（问答出题/海龟汤判定+猜汤底/成语裁判/卧底 AI 发言） |

**前端**

- mpostor.js：`MPOSTOR.auth`（get/require/logout）+ `MPOSTOR.voice`（speak/listen/attachMic）+ 统计按账号隔离
- 新增：login 页、4 个联机房间页（bomb-room/idiom-room/turtle-room/quiz-room，各 HTML+CSS+JS 三件套）
- 改造：首页（账号名/退出/联机入口/类型徽章）、大厅 5 选项卡、game-play/game-setup/game-result 登录门禁
- 单机 5 页：bug 修复 + TTS/麦克风接入 + 登录门禁

**Bug 修复（7 处）**

1. 数字炸弹第一猜后输入框禁用不复位（BLOCKER，用户实锤）
2. 数字炸弹 BOOM/SURVIVED 终局区间条不刷新
3. 数字炸弹终局状态样式缺失（.status.ended）
4. 海龟汤 startNewSoup 连点重入（busy 防重）
5. 问答 setTimeout 无题防御（提示异常并恢复操作）
6. 大厅 roomCreated 读 `message.data` 创建后不跳转
7. 大厅 joinSuccess 读 `message.data.playerId`

### 验证记录

- 后端 `./mvnw -q compile` 通过；重启成功（日志 backend.log）
- 前端 16 个 JS 文件 `node --check` 语法全过
- REST + auth 复测全过；WS auth 门禁冒烟 5/5
- **WS 4 场景冒烟 77/77 全过**（bomb 含 freshBomb 重埋与对局中 rejoin 快照防泄题；turtle 含 20 问上限终局附汤底；quiz 含答案不泄漏与排名；idiom 含裁判/跳过/犯规）
- 冒烟期间修复一处**测试脚本缺陷**（freshBomb 断言未区分终局与重埋分支，后端行为本身正确）

### 三期（v2.1：拆弹重构 + 阻碍 + 难度 + 开局过渡）

**拆弹重构（数字炸弹  拆弹）**

- 拆弹小队（bomb-room）/ 拆弹专家（number-bomb）：引爆密码藏在开区间，剪中密码线即**拆弹获胜**（DEFUSED），剪线次数耗尽全员阵亡（BOOM）；全程不下发密码，区间收窄提示
- 后端 `BombController` + `BombRoomLogic` 重写，消息流兼容旧客户端

**阻碍线系统（防纯二分通关）**

| 阻碍 | 机制 |
|---|---|
| 干扰线 DECOY | 剪断消耗 1 次剪线，**区间不收窄**，公开该线（剪过的干扰线即移除，再剪安全） |
| 熔断线 FUSE | 剪断额外多消耗 1 次剪线，区间正常收窄，公开该线 |

- 剪线上限 = 二分最优次数 + 阻碍半补偿：命中全部阻碍线必然 BOOM，纯二分不再稳赢
- 开局只广播 `decoyCount/fuseCount` 数量，**位置与密码一样永不泄漏**（REST/WS 一致）

**难度系统（创建房间时选择）**

- `createRoom` data 加 `difficulty`（easy|normal|hard，非法回落 normal），`RoomData.difficulty` 随房间携带，各游戏开局消息全部回显
- 谁是卧底：三档词库 —— 困难档为**形容词强化的近似词对**（如 冰镇西瓜/常温西瓜，10 类 × 3 档）
- 拆弹：easy 无障碍线；hard 阻碍密度翻倍（REST：4+2 根  9+4 根；WS：2+1 根  3+2 根）
- 成语接龙/海龟汤/趣味问答：LLM 提示词按难度调节（基础成语/简单场景/常识题  生僻成语/多重反转/冷知识）
- 大厅两表单共用难度选择器（记忆 localStorage），房间卡片显示难度徽章；单机拆弹页也有难度选择

**开局过渡动画**

- `MPOSTOR.fx.deployTransition(label)`：等待  对局的全屏工业风部署遮罩（DEPLOY 大标题 + 逐行自检 + 警示条纹 + 渐出），挂在 5 个游戏页的开局入口（卧底在待命区开局/重连进对局时触发 / 4 房间 started）

**成就栏（Achievement Wall）**

- 首页新增 `#achPanel`：19 项成就网格陈列，已解锁绿色 + 解锁日期，未解锁灰暗 LOCKED
- 解锁即刷新；隐藏终端新增 `ach` 指令（滚动定位到成就栏 + 故障闪烁）

**卧底对局流程修复（v2.1 补丁）**

- 症状：创建房间后落到对局页只见发言界面（提示词「???」），任何操作报「游戏未开始」，且无开局/解散入口
- 对局页新增**待命区** `#phaseWait`：房主见「» 开始游戏 / 解散房间」，非房主见等待提示；待命期不再显示发言框
- 开局流转正：房主在待命区点开始  全员就地收 `gameStarted`（含词对/难度/全员词表） 部署过渡  进入发言；大厅停留者仍走原跳转路径
- 解散功能：房主可在对局页解散；修复 `roomClosed` 广播静默丢弃 bug（`closeRoom` 先删房导致 `broadcastToRoom` 查不到房间，改为权限预检  广播  删房）
- 待命期间成员进出自动重拉快照；开局竞争（他人已开）静默同步；按钮防卡死重置

**其他**

- **全站 emoji 清除**：前端 UI、后端日志、README 全部改用文本标签（`[BOMB]` 等）与单色符号（   等）
- 后端修复 4 处：bomb-rest 范围校验（非法范围不吞校验）、bomb 终局后置校验 404、createRoom 数据校验、rejoin 空 data 防御
- 修复 `generateWordPair` 空类别 NPE（Map.ofEntries 不允许 null 键，类别缺省回退「食物」）

### 验证记录（v2.1）

- 后端 `./mvnw clean compile -q` 通过；重启成功
- **拆弹 REST 冒烟 53/53**（含 DECOY/FUSE 机制、剪线预算算术、BOOM 路径、小范围高密度确定性观察、难度档位 easy/hard/非法回落）
- **WS 5 场景冒烟 112/112**（bomb 含威胁计数/两房终局/难度房 easy+hard；undercover 含困难档形容词词对；idiom/turtle/quiz 全流程）
- **卧底浏览器流冒烟 34/34**（大厅创建  待命快照  待命区开局  全员收 gameStarted  解散广播  死房 rejoin 报错）
- 前端改动 JS `node --check` 全过；页面 200；live-server 3000 / 后端 8080 正常

## 十一、已知边界

- 账号/房间为内存态，重启即清空；无数据库持久层
- LLM 依赖外部 API：限流（429）或超时会降级（Mock 或本地题库），对局不中断
- 语音输入依赖浏览器 SpeechRecognition（Chrome/Edge 可用，Firefox 隐藏麦克风按钮）
- 联机模式为「真人同房」，AI 不自动补位（非卧底房 aiCount 强制为 0）
