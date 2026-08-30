const BASE = 'http://localhost:8080';
const uniq = Date.now().toString(36);
async function api(path, body, token) {
    const r = await fetch(BASE + path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
        body: JSON.stringify(body)
    });
    return { status: r.status, body: await r.json() };
}
function ck(name, cond, extra) { console.log((cond ? 'PASS' : 'FAIL') + '  ' + name + (cond ? '' : '  ' + JSON.stringify(extra))); }

// ---- auth ----
const u = 'restuser_' + uniq;
let r = await api('/api/auth/register', { username: u, password: 'pw1234' });
ck('register', r.body.code === 0, r.body);
r = await api('/api/auth/register', { username: u, password: 'pw1234' });
ck('register dup rejected', r.body.code !== 0, r.body);
r = await api('/api/auth/login', { username: u, password: 'pw1234' });
const token = r.body.data && r.body.data.token;
ck('login', r.body.code === 0 && !!token, r.body);
r = await api('/api/auth/login', { username: u, password: 'wrong' });
ck('login bad pwd rejected', r.body.code !== 0, r.body);
r = await api('/api/auth/me', {}, token);
ck('me', r.body.code === 0 && r.body.data && r.body.data.username === u, r.body);
r = await api('/api/auth/logout', {}, token);
ck('logout', r.body.code === 0, r.body);
r = await api('/api/auth/me', {}, token);
ck('me after logout rejected', r.body.code !== 0, r.body);
// short user/pass
r = await api('/api/auth/register', { username: 'a', password: 'pw1234' });
ck('register short name rejected', r.body.code !== 0, r.body);
r = await api('/api/auth/register', { username: 'okname', password: '12' });
ck('register short pwd rejected', r.body.code !== 0, r.body);

// ---- REST games (single-player behavior unchanged) ----
r = await api('/api/game/bomb/start', {});
ck('bomb start', r.body.code === 0 && r.body.data && r.body.data.min === 1, r.body);
const bombGame = r.body.data;
r = await api('/api/game/bomb/guess', { gameId: bombGame.gameId, number: 50 });
ck('bomb guess', r.body.code === 0, r.body);

r = await api('/api/game/idiom/start', {});
ck('idiom start', r.body.code === 0 && r.body.data && r.body.data.firstIdiom && r.body.data.lastChar, r.body);

r = await api('/api/game/turtle/start', {});
ck('turtle start', r.body.code === 0 && r.body.data && r.body.data.story && r.body.data.gameId, r.body);
const t = r.body.data;
r = await api('/api/game/turtle/ask', { gameId: t.gameId, question: '事情发生在白天吗？' });
ck('turtle ask', r.body.code === 0 && r.body.data && ['是','否','无法确定'].includes(r.body.data.answer), r.body);

r = await api('/api/game/quiz/start', {});
ck('quiz start', r.body.code === 0 && r.body.data && r.body.data.question && r.body.data.options, r.body);
const q = r.body.data;
r = await api('/api/game/quiz/answer', { gameId: q.gameId, index: 0 });
ck('quiz answer', r.body.code === 0, r.body);

r = await api('/api/game/guess/hint', { guess: '苹果', target: '香蕉' });
ck('guess hint', r.body.code === 0, r.body);
