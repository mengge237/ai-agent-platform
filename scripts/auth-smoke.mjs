// M3 WS auth 门禁冒烟（修正版：字段名 name/gameType，token 不复用已登出的）
const URL = 'ws://localhost:8080/ws/game';

function connect() {
  return new Promise((res, rej) => {
    const ws = new WebSocket(URL);
    ws.onopen = () => res(ws);
    ws.onerror = () => rej(new Error('ws connect failed'));
  });
}

function send(ws, obj, label) {
  return new Promise((res) => {
    const timer = setTimeout(() => { console.log(`  [${label}] timeout, no reply`); res(null); }, 3000);
    const h = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.type === 'connected') return;
      clearTimeout(timer);
      ws.removeEventListener('message', h);
      console.log(`  [${label}] <-`, m.type, '|', m.message, m.data && m.data.gameType ? `gameType=${m.data.gameType}` : '');
      res(m);
    };
    ws.addEventListener('message', h);
    ws.send(JSON.stringify(obj));
  });
}

const api = (path, body) => fetch('http://localhost:8080' + path, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
}).then(r => r.json());

await api('/api/auth/register', { username: '烟测员', password: 'pass1234' });
const t1 = (await api('/api/auth/login', { username: '烟测员', password: 'pass1234' })).data.token;

console.log('== 1. 未认证 createRoom（应 error 请先登录） ==');
const ws1 = await connect();
await send(ws1, { type: 'createRoom', playerId: 'P1', data: { name: '未登录房' } }, 'unauth-createRoom');

console.log('== 2. 认证（有效 token） ==');
await send(ws1, { type: 'auth', data: { token: t1 } }, 'auth');

console.log('== 3. 认证后 createRoom bomb（应 roomCreated gameType=bomb） ==');
await send(ws1, { type: 'createRoom', playerId: 'P1', data: { name: '已登录房', gameType: 'bomb' } }, 'auth-createRoom');

console.log('== 4. 冒充：第二账号用 P1 建房（应 error 已被占用） ==');
await api('/api/auth/register', { username: '冒名者', password: 'pass1234' });
const t2 = (await api('/api/auth/login', { username: '冒名者', password: 'pass1234' })).data.token;
const ws2 = await connect();
await send(ws2, { type: 'auth', data: { token: t2 } }, 'auth2');
await send(ws2, { type: 'createRoom', playerId: 'P1', data: { name: '冒充房', gameType: 'bomb' } }, 'impersonate');

console.log('== 5. 同账号另一会话用 P1 joinRoom 列表（应正常，不报占用） ==');
const ws3 = await connect();
await send(ws3, { type: 'auth', data: { token: t1 } }, 'auth3');
await send(ws3, { type: 'listRooms', playerId: 'P9' }, 'listRooms');

ws1.close(); ws2.close(); ws3.close();
console.log('DONE');
