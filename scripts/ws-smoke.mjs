// 联机新游戏 WS 冒烟：bomb / idiom / turtle / quiz / undercover(难度)
// 用法: node ws-smoke.mjs [bomb|idiom|turtle|quiz|undercover|all]
const SCENARIO = process.argv[2] || 'all';
const WS_URL = 'ws://localhost:8080/ws/game';
const API = 'http://localhost:8080';

let failures = 0;
let checks = 0;
function check(name, cond, detail = '') {
  checks++;
  if (cond) console.log(`  ✓ ${name}${detail ? ' | ' + detail : ''}`);
  else { failures++; console.log(`  ✕ ${name}${detail ? ' | ' + detail : ''}`); }
}

async function api(path, body) {
  const r = await fetch(API + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  return r.json();
}

class Player {
  constructor(name) { this.name = name; this.ws = null; this.queue = []; this.waiters = []; this.playerId = null; }
  async connect() {
    this.ws = new WebSocket(WS_URL);
    await new Promise((res, rej) => { this.ws.onopen = res; this.ws.onerror = () => rej(new Error('ws failed')); });
    this.ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (this.waiters.length) this.waiters.shift()(m);
      else this.queue.push(m);
    };
    this.ws.onerror = () => {};
  }
  send(obj) { this.ws.send(JSON.stringify(obj)); }
  /** 清掉队列中指定类型的旧广播（房间广播人人收到，别把它当成自己的响应） */
  drain(type) { this.queue = this.queue.filter(m => m.type !== type); }
  /** 等一条满足谓词的消息（带超时），不匹配的消息跳过继续等 */
  waitMatch(pred, timeout = 8000) {
    return new Promise((res) => {
      let done = false;
      const finish = (m) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        const i = this.waiters.indexOf(check);
        if (i >= 0) this.waiters.splice(i, 1);
        res(m);
      };
      const check = (m) => {
        if (done) return;
        if (m.type === 'error' || pred(m)) finish(m);
        else this.waiters.push(check); // 不匹配：自己排回队尾继续等
      };
      const timer = setTimeout(() => finish(null), timeout);
      const idx = this.queue.findIndex(m => m.type === 'error' || pred(m));
      if (idx >= 0) {
        const m = this.queue.splice(idx, 1)[0];
        finish(m);
        return;
      }
      this.waiters.push(check);
    });
  }
  /** 等一条 type 匹配的消息（带超时），不匹配的消息跳过继续等 */
  wait(type, timeout = 8000) {
    return new Promise((res) => {
      let done = false;
      const finish = (m) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        const i = this.waiters.indexOf(check);
        if (i >= 0) this.waiters.splice(i, 1);
        res(m);
      };
      const check = (m) => {
        if (done) return;
        if (m.type === type || m.type === 'error') finish(m);
        else this.waiters.push(check); // 不匹配：自己排回队尾继续等
      };
      const timer = setTimeout(() => finish(null), timeout);
      const idx = this.queue.findIndex(m => m.type === type || m.type === 'error');
      if (idx >= 0) {
        const m = this.queue.splice(idx, 1)[0];
        finish(m);
        return;
      }
      this.waiters.push(check);
    });
  }
  async register() {
    await api('/api/auth/register', { username: this.name, password: 'pass1234' });
    const login = await api('/api/auth/login', { username: this.name, password: 'pass1234' });
    this.token = login.data.token;
  }
  async auth() {
    this.send({ type: 'auth', data: { token: this.token } });
    const m = await this.wait('authSuccess');
    check(`${this.name} auth`, m && m.type === 'authSuccess');
  }
  close() { try { this.ws.close(); } catch (e) {} }
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function createRoom(host, gameType) {
  const m = await host.wait('roomCreated');
  return m.data.id;
}
async function hostCreates(host, gameType, roomName, difficulty) {
  host.send({ type: 'createRoom', playerId: host.playerId, data: { name: roomName, gameType, difficulty } });
  const m = await host.wait('roomCreated');
  check(`房间创建 ${gameType}`, m && m.type === 'roomCreated' && m.data.gameType === gameType, m && m.data.id);
  if (difficulty) check(`房间难度回显 ${difficulty}`, m && m.data.difficulty === difficulty, m && m.data.difficulty);
  return m.data.id;
}
async function joinRoom(p, roomId) {
  p.send({ type: 'joinRoom', playerId: p.playerId, data: { roomId } });
  const m = await p.wait('roomJoined');
  check(`${p.name} joinRoom`, m && m.type === 'roomJoined');
}
async function startGame(host, roomId, startedType) {
  host.send({ type: 'startGame', playerId: host.playerId, data: { roomId } });
  // 开局可能触发 LLM（30s 最坏超时），等待放宽
  const m = await host.wait(startedType, 40000);
  check(`startGame -> ${startedType}`, m && m.type === startedType, m && m.type);
  return m;
}

// ================= bomb (拆弹) =================
async function bombScenario() {
  console.log('===== BOMB (拆弹) =====');
  const A = new Player('bombA'), B = new Player('bombB'), C = new Player('bombC');
  A.playerId = 'PB-A'; B.playerId = 'PB-B'; C.playerId = 'PB-C';
  await A.connect(); await B.connect(); await C.connect();
  await A.register(); await B.register(); await C.register();
  await A.auth(); await B.auth(); await C.auth();

  const roomId = await hostCreates(A, 'bomb', '拆弹房');
  await joinRoom(B, roomId);
  await joinRoom(C, roomId);

  // 未开局剪线应报错
  B.drain('error');
  B.send({ type: 'bombGuess', playerId: B.playerId, data: { roomId, number: 50 } });
  check('未开局剪线被拒', (await B.wait('error', 4000))?.type === 'error');

  const started = await startGame(A, roomId, 'bombStarted');
  const maxCuts = 4 + 2 * 3 + 2; // 3 人小队 = 10 次 + 阻碍补偿 2 = 12
  check('bombStarted 带剪线上限', started && started.type === 'bombStarted' && started.min === 1 && started.max === 100 && started.maxCuts === maxCuts,
    started && JSON.stringify({ maxCuts: started.maxCuts, min: started.min, max: started.max }));
  check('bombStarted 带威胁计数', started && started.decoyCount === 2 && started.fuseCount === 1,
    started && JSON.stringify({ decoyCount: started.decoyCount, fuseCount: started.fuseCount }));
  if (!started || started.type !== 'bombStarted') { A.close(); B.close(); C.close(); return; }
  const b1 = await B.wait('bombStarted');
  check('玩家收到 bombStarted', b1 && b1.type === 'bombStarted' && b1.maxCuts === maxCuts);
  check('首发者存在', typeof started.currentTurn === 'string');

  // 错回合剪线被拒
  const notTurn = started.currentTurn === A.playerId ? B : A;
  notTurn.drain('error');
  notTurn.send({ type: 'bombGuess', playerId: notTurn.playerId, data: { roomId, number: 50 } });
  check('错回合剪线被拒', (await notTurn.wait('error', 4000))?.type === 'error');

  // 中局断线快速重连（5s 宽限内）：无缝恢复，不判离场，rejoin 快照不泄题
  await A.close();
  const A2 = new Player('bombA'); A2.playerId = A.playerId; A2.token = A.token;
  await A2.connect(); await A2.auth();
  A2.send({ type: 'rejoinRoom', playerId: A2.playerId, data: { roomId } });
  const rj = await A2.wait('rejoinSuccess', 4000);
  if (rj && rj.type === 'rejoinSuccess') {
    const dumped = JSON.stringify(rj.data);
    check('对局中 rejoin 成功', rj.data.status === 'playing', JSON.stringify({ host: rj.data.host, name: rj.data.name }));
    check('rejoin 快照无炸弹密码', !dumped.includes('bomb.value'));
    check('rejoin 带 gameType/displayNames', rj.data.gameType === 'bomb' && !!rj.data.displayNames);
    check('rejoin 带区间/回合/alive', typeof rj.data.min === 'number' && typeof rj.data.max === 'number' && typeof rj.data.currentTurn === 'string' && Array.isArray(rj.data.alive));
    check('rejoin 带难度', rj.data.difficulty === 'normal', String(rj.data.difficulty));
    check('rejoin 带剪线上限/剩余', typeof rj.data.maxCuts === 'number' && rj.data.cutsLeft === maxCuts, JSON.stringify({ maxCuts: rj.data.maxCuts, cutsLeft: rj.data.cutsLeft }));
    check('rejoin 快照带威胁计数（不泄位置）', rj.data.decoyCount === 2 && rj.data.fuseCount === 1, JSON.stringify({ decoyCount: rj.data.decoyCount, fuseCount: rj.data.fuseCount }));
    check('快速重连不判离场', rj.data.alive.includes(A.playerId) && !(rj.data.eliminated || []).includes(A.playerId));
  } else {
    check('对局中 rejoin 成功', false, rj && (rj.message || rj.type));
  }

  // 三人二分剪线：阻碍线可能吞信息（DECOY 区间不变）或吞次数（FUSE 额外消耗），
  // 最坏 7 剪 + 3 次阻碍损耗 = 10 ≤ 12，DEFUSED 必达
  const players = [A2, B, C];
  let done = false;
  let low = 1, high = 100;
  let turn = started.currentTurn;
  for (let i = 0; i < maxCuts + 4 && !done; i++) {
    const p = players.find(x => x.playerId === turn);
    const guess = Math.floor((low + high) / 2);
    const prevLow = low, prevHigh = high;
    p.drain('bombUpdate');
    p.send({ type: 'bombGuess', playerId: p.playerId, data: { roomId, number: guess } });
    // 广播人人收到，按 number 关联自己刚发的那一剪
    const u = await p.waitMatch(m => m.type === 'bombUpdate' && m.number === guess, 6000);
    if (!u) { check(`第${i + 1}剪有回复`, false); break; }
    if (u.type === 'error') { check(`第${i + 1}剪合法`, false, u.message); break; }
    low = u.low; high = u.high;
    if (u.result === 'SAFE' || u.result === 'FUSE') {
      check(`第${i + 1}剪 ${guess} 是${u.result === 'FUSE' ? '熔断' : '安全'}线`,
        (u.low === prevLow || u.high === prevHigh) && u.low <= u.high && u.cutsLeft === maxCuts - u.attempts,
        `区间${low}~${high} 剩${u.cutsLeft}次`);
      if (u.result === 'FUSE') check('FUSE 公开该线', u.fuse === guess, `fuse=${u.fuse}`);
      turn = u.nextPlayer;
    } else if (u.result === 'DECOY') {
      check(`第${i + 1}剪 ${guess} 是干扰线（区间不变）`, u.decoy === guess && u.low === prevLow && u.high === prevHigh && u.cutsLeft === maxCuts - u.attempts,
        `区间${low}~${high} 剩${u.cutsLeft}次`);
      turn = u.nextPlayer;
    } else if (u.result === 'DEFUSED') {
      done = true;
      check('DEFUSED 揭晓密码线', u.code === guess, `code=${u.code} guess=${guess}`);
      check('DEFUSED 剩余次数非负', typeof u.cutsLeft === 'number' && u.cutsLeft >= 0, String(u.cutsLeft));
      const g = await p.wait('gameEnded', 4000);
      check('DEFUSED 剪线者获胜', g && g.type === 'gameEnded' && g.result === p.playerId, g && g.result);
      check('终局含全员排名', g && Array.isArray(g.ranking) && g.ranking.length === 3);
    } else if (u.result === 'BOOM') {
      done = true;
      check('BOOM 揭晓密码', typeof u.code === 'number', String(u.code));
      const g = await p.wait('gameEnded', 4000);
      check('BOOM 全员阵亡平局', g && g.type === 'gameEnded' && g.result === 'draw', g && g.result);
    }
  }
  check('对局在剪线上限内结束', done);

  // ---- 第二房：慢速离场（>5s 宽限）→ 回合持有者被除名，LEFT 让位 ----
  await C.wait('gameEnded', 4000); // 消耗 C 手里延迟到达的一房终局广播，避免污染二房判定
  B.queue = []; C.queue = [];
  const room2 = await hostCreates(A2, 'bomb', '拆弹房2');
  await joinRoom(B, room2);
  await joinRoom(C, room2);
  const s2 = await startGame(A2, room2, 'bombStarted');
  check('第二房开局带上限', s2 && s2.type === 'bombStarted' && s2.maxCuts === maxCuts);
  if (!s2 || s2.type !== 'bombStarted') { A2.close(); B.close(); C.close(); return; }
  await B.wait('bombStarted'); await C.wait('bombStarted');
  // 回合持有者（A2）离场且不重连 → 5s 宽限后 LEFT 让位
  await A2.close();
  const leftU = await B.waitMatch(m => m.type === 'bombUpdate' && m.result === 'LEFT', 12000);
  check('慢速离场 LEFT 让位', leftU && leftU.eliminated === A2.playerId && leftU.cutsLeft === maxCuts,
    leftU && JSON.stringify({ eliminated: leftU.eliminated, cutsLeft: leftU.cutsLeft, nextPlayer: leftU.nextPlayer }));
  if (!leftU) { B.close(); C.close(); return; }
  // 剩余 2 人继续二分 → DEFUSED（阻碍线同样生效）
  low = 1; high = 100; turn = leftU.nextPlayer; done = false;
  for (let i = 0; i < maxCuts + 4 && !done; i++) {
    const p = turn === B.playerId ? B : C;
    const guess = Math.floor((low + high) / 2);
    p.drain('bombUpdate');
    p.send({ type: 'bombGuess', playerId: p.playerId, data: { roomId: room2, number: guess } });
    const u = await p.waitMatch(m => m.type === 'bombUpdate' && m.number === guess, 6000);
    if (!u || u.type === 'error') { check(`二房第${i + 1}剪合法`, false, u && u.message); break; }
    low = u.low; high = u.high;
    if (u.result === 'SAFE' || u.result === 'FUSE' || u.result === 'DECOY') {
      turn = u.nextPlayer;
    } else if (u.result === 'DEFUSED') {
      done = true;
      const g = await p.wait('gameEnded', 4000);
      check('二房 DEFUSED 获胜', g && g.result === p.playerId, g && g.result);
      check('二房排名不含离场者', g && Array.isArray(g.ranking) && g.ranking.length === 2);
    } else if (u.result === 'BOOM') {
      done = true;
      const g = await p.wait('gameEnded', 4000);
      check('二房 BOOM 平局', g && g.result === 'draw');
    }
  }
  check('二房对局结束', done);

  // ---- 难度房：easy 无阻碍 / hard 阻碍加密（2 人小队） ----
  // 玩家不能同时处于多个非终局房间：easy 房验证后全员断线终局，再开 hard 房
  B.queue = []; C.queue = [];
  const roomE = await hostCreates(B, 'bomb', '简单拆弹房', 'easy');
  await joinRoom(C, roomE);
  const sE = await startGame(B, roomE, 'bombStarted');
  check('easy 房无阻碍', sE && sE.difficulty === 'easy' && sE.decoyCount === 0 && sE.fuseCount === 0 && sE.maxCuts === 8,
    sE && JSON.stringify({ difficulty: sE.difficulty, decoyCount: sE.decoyCount, fuseCount: sE.fuseCount, maxCuts: sE.maxCuts }));
  await C.wait('bombStarted');
  B.close(); C.close();
  await sleep(8000); // 5s 离场宽限 + 终局结算缓冲
  const B2 = new Player('bombB'); B2.playerId = B.playerId; B2.token = B.token;
  const C2 = new Player('bombC'); C2.playerId = C.playerId; C2.token = C.token;
  await B2.connect(); await B2.auth(); await C2.connect(); await C2.auth();
  const roomH = await hostCreates(B2, 'bomb', '困难拆弹房', 'hard');
  await joinRoom(C2, roomH);
  const sH = await startGame(B2, roomH, 'bombStarted');
  check('hard 房阻碍加密', sH && sH.difficulty === 'hard' && sH.decoyCount === 3 && sH.fuseCount === 2 && sH.maxCuts === 11,
    sH && JSON.stringify({ difficulty: sH.difficulty, decoyCount: sH.decoyCount, fuseCount: sH.fuseCount, maxCuts: sH.maxCuts }));
  await C2.wait('bombStarted');
  B2.close(); C2.close();
}

// ================= undercover (难度·形容词词对) =================
async function undercoverScenario() {
  console.log('===== UNDERCOVER (难度) =====');
  const A = new Player('ucA'), B = new Player('ucB'), C = new Player('ucC');
  A.playerId = 'PU-A'; B.playerId = 'PU-B'; C.playerId = 'PU-C';
  await A.connect(); await B.connect(); await C.connect();
  await A.register(); await B.register(); await C.register();
  await A.auth(); await B.auth(); await C.auth();

  // 困难房：形容词强化的近似词对
  const roomId = await hostCreates(A, 'undercover', '卧底困难房', 'hard');
  await joinRoom(B, roomId);
  await joinRoom(C, roomId);
  const started = await startGame(A, roomId, 'gameStarted');
  check('gameStarted 带难度', started && started.difficulty === 'hard', started && started.difficulty);
  check('gameStarted 下发近似词对', started && !!started.civilianWord && !!started.undercoverWord && started.civilianWord !== started.undercoverWord,
    started && JSON.stringify({ civilianWord: started.civilianWord, undercoverWord: started.undercoverWord }));
  const b = await B.wait('gameStarted');
  check('玩家收到 gameStarted', b && b.type === 'gameStarted' && b.difficulty === 'hard', b && b.difficulty);
  await C.wait('gameStarted');
  A.close(); B.close(); C.close();
}

// ================= idiom =================
async function idiomScenario() {
  console.log('===== IDIOM =====');
  const A = new Player('idiomA'), B = new Player('idiomB');
  A.playerId = 'PI-A'; B.playerId = 'PI-B';
  await A.connect(); await B.connect();
  await A.register(); await B.register();
  await A.auth(); await B.auth();

  const roomId = await hostCreates(A, 'idiom', '接龙房');
  await joinRoom(B, roomId);
  const started = await startGame(A, roomId, 'idiomStarted');
  check('idiomStarted 带首词', !!started && !!started.firstIdiom && !!started.lastChar, started && started.firstIdiom);
  if (!started || started.type !== 'idiomStarted') { A.close(); B.close(); return; }
  await B.wait('idiomStarted');

  // 首字错 → 犯规
  const turn = started.currentTurn;
  const p = turn === A.playerId ? A : B;
  p.drain('idiomUpdate');
  p.send({ type: 'idiomPlay', playerId: p.playerId, data: { roomId, idiom: '天上人间' } });
  const u1 = await p.waitMatch(m => m.type === 'idiomUpdate' && m.playerName === p.playerId, 8000);
  check('首字错记犯规', u1 && u1.type === 'idiomUpdate' && u1.valid === false, u1 && u1.reason);
  check('犯规后换人', u1 && u1.nextPlayer !== p.playerId);
  if (!u1) { A.close(); B.close(); return; }
  let cur = u1.nextPlayer;

  // 有效接龙（用本地字典兜底：以 lastChar 开头的常见成语）
  const lastChar = started.lastChar;
  const idiom = IDIOM_STARTS[lastChar];
  if (idiom) {
    const q = cur === A.playerId ? A : B;
    q.drain('idiomUpdate');
    q.send({ type: 'idiomPlay', playerId: q.playerId, data: { roomId, idiom } });
    // LLM 裁判可能较慢，等待放宽
    const u2 = await q.waitMatch(m => m.type === 'idiomUpdate' && m.playerName === q.playerId, 40000);
    check('有效接龙通过', u2 && u2.type === 'idiomUpdate' && u2.valid === true, u2 && u2.idiom);
    if (u2 && !u2.valid) check('裁判给的原因', false, u2.reason);
    if (u2) cur = u2.nextPlayer;
  } else {
    console.log(`  [!] 字典缺少末字「${lastChar}」，跳过有效接龙测试`);
  }

  // 房主跳过当前玩家
  A.drain('idiomUpdate');
  A.send({ type: 'idiomSkip', playerId: A.playerId, data: { roomId } });
  const u3 = await A.waitMatch(m => m.type === 'idiomUpdate' && m.skipped === true, 8000);
  check('房主跳过生效', !!u3 && u3.skipped === true, u3 && u3.reason);
  A.close(); B.close();
}
// 末字 → 以该字开头的常见四字成语（LLM 首词末字不可预测，尽量覆盖）
const IDIOM_STARTS = {
  '兔': '兔死狐悲', '悲': '悲天悯人', '人': '人山人海', '海': '海阔天空', '空': '空前绝后', '后': '后来居上', '上': '上行下效',
  '天': '天长地久', '意': '意气风发', '发': '发扬光大', '大': '大器晚成', '成': '成千上万', '万': '万紫千红', '红': '红光满面', '面': '面面俱到',
  '功': '功成名就', '琴': '琴棋书画', '画': '画龙点睛', '牢': '牢不可破', '破': '破釜沉舟', '舟': '舟车劳顿', '顿': '顿开茅塞',
  '竹': '竹报平安', '安': '安然无恙', '鼠': '鼠目寸光', '光': '光明正大', '尾': '尾大不掉', '掉': '掉以轻心', '心': '心花怒放',
  '放': '放虎归山', '山': '山清水秀', '秀': '秀外慧中', '中': '中流砥柱', '舞': '舞文弄墨', '墨': '墨守成规', '龙': '龙腾虎跃',
  '马': '马到成功', '足': '足智多谋', '谋': '谋事在人', '云': '云开雾散', '散': '散兵游勇', '壮': '壮志凌云', '理': '理直气壮', '强': '强词夺理',
  '风': '风和日丽', '日': '日新月异', '异': '异口同声', '声': '声东击西', '水': '水落石出', '出': '出人头地', '地': '地大物博', '博': '博学多才',
  '才': '才高八斗', '斗': '斗转星移', '移': '移花接木', '木': '木已成舟', '门': '门庭若市', '一': '一心一意', '三': '三心二意', '五': '五花八门',
  '四': '四面八方', '八': '八仙过海', '千': '千军万马', '百': '百发百中', '七': '七上八下', '九': '九牛一毛', '如': '如鱼得水', '鱼': '鱼目混珠',
  '手': '手忙脚乱', '言': '言而有信', '信': '信誓旦旦', '行': '行云流水', '真': '真相大白', '深': '深谋远虑', '年': '年富力强', '春': '春暖花开',
  '开': '开门见山', '长': '长命百岁', '高': '高瞻远瞩', '远': '远走高飞', '飞': '飞黄腾达', '名': '名副其实', '实': '实至名归', '归': '归根结底',
  '非': '非同小可', '同': '同心协力', '力': '力挽狂澜', '尽': '尽力而为', '为': '为所欲为', '兴': '兴高采烈', '入': '入木三分', '分': '分秒必争',
  '争': '争先恐后', '语': '语重心长', '重': '重见天日', '想': '想入非非', '知': '知己知彼', '道': '道听途说', '说': '说三道四', '走': '走马观花',
  '见': '见义勇为', '勇': '勇往直前', '前': '前赴后继', '来': '来龙去脉', '得': '得天独厚', '失': '失之交臂', '完': '完璧归赵', '满': '满载而归',
  '乐': '乐在其中', '平': '平步青云', '青': '青出于蓝', '秋': '秋高气爽', '南': '南辕北辙', '东': '东山再起', '起': '起死回生', '金': '金榜题名',
  '半': '半途而废', '独': '独一无二', '数': '数一数二', '众': '众志成城', '全': '全力以赴', '神': '神出鬼没', '鬼': '鬼斧神工', '生': '生龙活虎',
  '虎': '虎头蛇尾', '蛇': '蛇蝎心肠', '无': '无中生有', '有': '有口皆碑', '下': '下笔成章', '久': '久别重逢', '死': '死里逃生', '白': '白手起家',
  '家': '家喻户晓', '明': '明察秋毫', '花': '花好月圆', '绿': '绿草如茵',
};

// ================= turtle =================
async function turtleScenario() {
  console.log('===== TURTLE =====');
  const H = new Player('turtleH'), G = new Player('turtleG');
  H.playerId = 'PT-H'; G.playerId = 'PT-G';
  await H.connect(); await G.connect();
  await H.register(); await G.register();
  await H.auth(); await G.auth();

  const roomId = await hostCreates(H, 'turtle', '海龟汤房');
  await joinRoom(G, roomId);
  await startGame(H, roomId, 'turtleStarted');

  // 房主收到汤底（私密），玩家收到 started（无汤底）
  const started = await G.wait('turtleStarted', 10000);
  const secret = await H.wait('turtleHostSecret', 10000);
  check('房主收到汤底', !!secret && secret.type === 'turtleHostSecret' && !!secret.answer);
  check('玩家收到汤面', !!started && started.type === 'turtleStarted' && !!started.story);
  check('汤面不含汤底', !(JSON.stringify(started || {}).includes(secret && secret.answer)));

  // 非房主提问 → 房主判定
  G.send({ type: 'turtleAsk', playerId: G.playerId, data: { roomId, question: '汤里的东西是人肉吗？' } });
  const asked = await G.wait('turtleAsked', 8000);
  const askedH = await H.wait('turtleAsked', 8000);
  check('提问广播双方可见', asked && asked.type === 'turtleAsked' && askedH && askedH.type === 'turtleAsked', asked && asked.no);
  H.send({ type: 'turtleAnswer', playerId: H.playerId, data: { roomId, questionNo: asked.no, answer: '是', hint: '和吃有关' } });
  const ans = await G.wait('turtleAnswered', 8000);
  check('判定广播', ans && ans.type === 'turtleAnswered' && ans.answer === '是');

  // 猜错 → 提示继续
  G.send({ type: 'turtleGuess', playerId: G.playerId, data: { roomId, guess: '他吃了一条鱼' } });
  // 猜汤底判定走 LLM，可能较慢
  const gr = await G.wait('turtleGuessResult', 40000);
  check('猜错返回结果', gr && gr.type === 'turtleGuessResult' && typeof gr.correct === 'boolean', gr && gr.comment);

  // 房主提前揭晓
  H.send({ type: 'turtleReveal', playerId: H.playerId, data: { roomId } });
  const rev = await G.wait('turtleRevealed', 8000);
  const end = await G.wait('gameEnded', 8000);
  check('揭晓广播+gameEnded(房主胜)', rev && rev.type === 'turtleRevealed' && !!rev.answer && end && end.result === H.playerId);

  // 第二房：20 问上限 → 第 20 问判定后自动终局（房主胜），gameEnded 附汤底
  H.drain('turtleStarted'); G.drain('turtleStarted');
  const room2 = await hostCreates(H, 'turtle', '海龟汤20问房');
  await joinRoom(G, room2);
  await startGame(H, room2, 'turtleStarted');
  await H.wait('turtleHostSecret', 10000);
  await G.wait('turtleStarted', 10000);
  let askedOk = true;
  for (let i = 1; i <= 20; i++) {
    G.send({ type: 'turtleAsk', playerId: G.playerId, data: { roomId: room2, question: `第${i}问：汤里有动物吗？` } });
    const a = await G.wait('turtleAsked', 8000);
    if (!a) { askedOk = false; break; }
    H.send({ type: 'turtleAnswer', playerId: H.playerId, data: { roomId: room2, questionNo: a.no, answer: '否' } });
    const an = await G.wait('turtleAnswered', 8000);
    if (!an) { askedOk = false; break; }
  }
  check('20 问全流程走通', askedOk);
  const end20 = await G.wait('gameEnded', 8000);
  check('20 问上限自动终局(房主胜)', end20 && end20.type === 'gameEnded' && end20.result === H.playerId, end20 && end20.result);
  check('20 问终局附汤底', end20 && !!end20.answer);
  H.close(); G.close();
}

// ================= quiz =================
async function quizScenario() {
  console.log('===== QUIZ =====');
  const A = new Player('quizA'), B = new Player('quizB');
  A.playerId = 'PQ-A'; B.playerId = 'PQ-B';
  await A.connect(); await B.connect();
  await A.register(); await B.register();
  await A.auth(); await B.auth();

  const roomId = await hostCreates(A, 'quiz', '问答房');
  await joinRoom(B, roomId);
  const started = await startGame(A, roomId, 'quizStarted');
  check('quizStarted 带题数', !!started && started.total >= 3 && started.total <= 5, started && started.total);
  if (!started || started.type !== 'quizStarted') { A.close(); B.close(); return; }
  const q1 = await A.wait('quizQuestion', 10000);
  await B.wait('quizQuestion');
  check('quizQuestion 无答案泄漏', q1 && !('correctIndex' in q1) && !('answerIndex' in q1) && !('explanation' in q1));

  const total = started.total;
  for (let i = 0; i < total; i++) {
    // 两人都答（随便答，答完自动揭晓）
    A.drain('quizAnswered'); A.drain('quizReveal'); A.drain('error');
    A.send({ type: 'quizAnswer', playerId: A.playerId, data: { roomId, index: 0 } });
    const pa = await A.wait('quizAnswered', 6000);
    if (i === 0) {
      // 重复答被拒
      A.drain('error');
      A.send({ type: 'quizAnswer', playerId: A.playerId, data: { roomId, index: 1 } });
      check('重复答题被拒', (await A.wait('error', 4000))?.type === 'error');
    }
    B.drain('quizAnswered'); B.drain('quizReveal');
    B.send({ type: 'quizAnswer', playerId: B.playerId, data: { roomId, index: 1 } });
    const rev = await A.wait('quizReveal', 10000);
    check(`第${i + 1}题自动揭晓`, rev && rev.type === 'quizReveal' && typeof rev.correctIndex === 'number', rev && rev.correctIndex);

    if (i < total - 1) {
      A.send({ type: 'quizNext', playerId: A.playerId, data: { roomId } });
      const qn = await A.wait('quizQuestion', 10000);
      check(`第${i + 2}题下发`, qn && qn.type === 'quizQuestion' && qn.questionNo === i + 2);
    }
  }
  // 最后：房主下一题 → gameEnded 排名
  A.send({ type: 'quizNext', playerId: A.playerId, data: { roomId } });
  const end = await A.wait('gameEnded', 10000);
  await B.wait('gameEnded', 10000);
  check('gameEnded 带排名', end && end.type === 'gameEnded' && Array.isArray(end.ranking) && end.ranking.length === 2, end && end.result);
  check('排名按分数排序', end && end.ranking[0].score >= end.ranking[1].score);
  A.close(); B.close();
}

// ============ main ============
const run = async () => {
  if (SCENARIO === 'bomb' || SCENARIO === 'all') await bombScenario();
  if (SCENARIO === 'idiom' || SCENARIO === 'all') await idiomScenario();
  if (SCENARIO === 'turtle' || SCENARIO === 'all') await turtleScenario();
  if (SCENARIO === 'quiz' || SCENARIO === 'all') await quizScenario();
  if (SCENARIO === 'undercover' || SCENARIO === 'all') await undercoverScenario();
  console.log(`\n===== 结果: ${checks - failures}/${checks} 通过${failures ? `，${failures} 失败` : ''} =====`);
  process.exit(failures ? 1 : 0);
};
run().catch(e => { console.error('SMOKE CRASH:', e); process.exit(2); });
