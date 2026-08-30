// 卧底浏览器流冒烟：大厅创建 → 对局页待命 → 房主在待命区开局 → 全员收 gameStarted → 房主解散广播
// 用法: node undercover-flow-smoke.mjs
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
  constructor(name) { this.name = name; this.ws = null; this.queue = []; this.waiters = []; this.playerId = null; this.token = null; }
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
  drain(type) { this.queue = this.queue.filter(m => m.type !== type); }
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
        else this.waiters.push(check);
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
        else this.waiters.push(check);
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
  async register(username) {
    await api('/api/auth/register', { username, password: 'pass1234' });
    const login = await api('/api/auth/login', { username, password: 'pass1234' });
    this.token = login.data.token;
  }
  async auth() {
    this.send({ type: 'auth', data: { token: this.token } });
    const m = await this.wait('authSuccess');
    check(`${this.name} auth`, m && m.type === 'authSuccess');
  }
  async join() {
    this.send({ type: 'join', playerId: this.playerId, data: { playerName: this.name } });
    const m = await this.wait('joinSuccess');
    check(`${this.name} join`, m && m.type === 'joinSuccess');
  }
  close() { try { this.ws.close(); } catch (e) {} }

  /** 模拟页面跳转：断开旧页面 socket，新页面重连 + auth + rejoin */
  async navigateToGame(roomId) {
    this.close();
    await sleep(300);
    await this.connect();
    await this.auth();
    this.send({ type: 'rejoinRoom', playerId: this.playerId, data: { roomId } });
    const m = await this.wait('rejoinSuccess', 10000);
    check(`${this.name} rejoinSuccess`, m && m.type === 'rejoinSuccess', m && m.type);
    return m;
  }
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function scenario() {
  const A = new Player('flowHost'); A.playerId = 'PF-A';
  const B = new Player('flowGuest'); B.playerId = 'PF-B';
  await A.register('flowHost'); await B.register('flowGuest');

  // ---- 1. 大厅：房主创建卧底房 ----
  console.log('--- 创建房间（大厅） ---');
  await A.connect(); await A.auth(); await A.join();
  A.send({ type: 'createRoom', playerId: A.playerId, data: { name: '流测卧底房', gameType: 'undercover', difficulty: 'easy', maxPlayers: 4, aiCount: 2, category: '水果', password: '' } });
  const created = await A.wait('roomCreated');
  const roomId = created && created.data && created.data.id;
  check('房主创建房间', created && created.type === 'roomCreated' && !!roomId, roomId);

  // ---- 2. 房主跳转对局页：待命快照 ----
  console.log('--- 房主进入对局页（待命） ---');
  const rjA = await A.navigateToGame(roomId);
  check('待命快照 status=waiting', rjA && rjA.type === 'rejoinSuccess' && rjA.data.status === 'waiting', rjA && rjA.data && rjA.data.status);
  check('待命快照 host 字段', rjA && rjA.data.host === A.playerId, rjA && rjA.data && rjA.data.host);
  check('待命快照玩家列表含 AI', rjA && Array.isArray(rjA.data.players) && rjA.data.players.length === 3, rjA && rjA.data && JSON.stringify(rjA.data.players));

  // ---- 3. 成员加入并跳转对局页 ----
  console.log('--- 成员加入 → 对局页（待命） ---');
  await B.connect(); await B.auth(); await B.join();
  B.send({ type: 'joinRoom', playerId: B.playerId, data: { roomId } });
  const joined = await B.wait('roomJoined');
  check('成员加入房间', joined && joined.type === 'roomJoined');
  const rjB = await B.navigateToGame(roomId);
  check('成员待命快照', rjB && rjB.type === 'rejoinSuccess' && rjB.data.status === 'waiting');
  check('成员快照 host 非本人', rjB && rjB.data.host === A.playerId);
  // 成员跳转后，房主应通过 playerJoined 刷新（此处只验证服务端允许二次 rejoin）
  const rjA2 = await A.navigateToGame(roomId);
  check('房主再次 rejoin 成功', rjA2 && rjA2.type === 'rejoinSuccess' && rjA2.data.players.length === 4, rjA2 && rjA2.data && JSON.stringify(rjA2.data.players));

  // ---- 4. 房主在对局页点开始：全员收到 gameStarted ----
  console.log('--- 待命区开局 ---');
  A.send({ type: 'startGame', playerId: A.playerId, data: { roomId } });
  const startedA = await A.waitMatch(m => m.type === 'gameStarted', 10000);
  const startedB = await B.waitMatch(m => m.type === 'gameStarted', 10000);
  check('房主收到 gameStarted', startedA && startedA.type === 'gameStarted', startedA && startedA.type);
  check('成员收到 gameStarted', startedB && startedB.type === 'gameStarted', startedB && startedB.type);
  check('gameStarted 下发词对', startedA && !!startedA.civilianWord && !!startedA.undercoverWord && startedA.civilianWord !== startedA.undercoverWord,
    startedA && JSON.stringify({ c: startedA.civilianWord, u: startedA.undercoverWord }));
  check('gameStarted 下发难度', startedA && startedA.difficulty === 'easy', startedA && startedA.difficulty);
  check('gameStarted 下发全员词表', startedA && startedA.playerWords && Object.keys(startedA.playerWords).length >= 4);
  const roundA = await A.waitMatch(m => m.type === 'roundStarted', 8000);
  check('随后 roundStarted 广播', roundA && roundA.type === 'roundStarted', roundA && roundA.type);

  // ---- 5. 解散房间：房主在对局页点解散，全员收到 roomClosed ----
  console.log('--- 解散房间（对局页按钮路径） ---');
  // 先离开当前对局房（终局后才能离场不卡人；直接重开新房间更简单）
  A.close(); B.close();
  await sleep(8000); // 5s 离场宽限 + 终局结算缓冲
  const A2 = new Player('flowHost2'); A2.playerId = A.playerId; A2.token = A.token;
  const B2 = new Player('flowGuest2'); B2.playerId = B.playerId; B2.token = B.token;
  await A2.connect(); await A2.auth();
  await B2.connect(); await B2.auth();
  A2.send({ type: 'createRoom', playerId: A2.playerId, data: { name: '流测解散房', gameType: 'undercover', difficulty: 'normal', maxPlayers: 4, aiCount: 1, category: '水果', password: '' } });
  const created2 = await A2.wait('roomCreated');
  const roomId2 = created2.data.id;
  check('创建第二房', !!roomId2);
  B2.send({ type: 'joinRoom', playerId: B2.playerId, data: { roomId: roomId2 } });
  await B2.wait('roomJoined');
  await A2.navigateToGame(roomId2);
  await B2.navigateToGame(roomId2);

  A2.drain('roomClosed'); B2.drain('roomClosed');
  A2.send({ type: 'closeRoom', playerId: A2.playerId, data: { roomId: roomId2 } });
  const closedA = await A2.wait('roomClosed', 8000);
  const closedB = await B2.wait('roomClosed', 8000);
  check('房主收到 roomClosed', closedA && closedA.type === 'roomClosed', closedA && closedA.type);
  check('成员收到 roomClosed 广播', closedB && closedB.type === 'roomClosed', closedB && closedB.type);
  // 解散后 rejoin：直接发送并等待 error
  A2.send({ type: 'rejoinRoom', playerId: A2.playerId, data: { roomId: roomId2 } });
  const rejoinDead = await A2.waitMatch(m => m.type === 'error', 8000);
  check('解散后 rejoin 报房间不存在', rejoinDead && rejoinDead.type === 'error', rejoinDead && rejoinDead.message);
  A2.close(); B2.close();

  console.log(`\n===== 结果: ${checks - failures}/${checks} 通过 =====`);
  process.exit(failures ? 1 : 0);
}

scenario().catch(e => { console.error('FAIL', e); process.exit(1); });
