// 拆弹专家 REST 冒烟：/api/game/bomb/start | /guess
// 阻碍系统：干扰线 DECOY（区间不变）+ 熔断线 FUSE（额外消耗 1 次）
// 用法: node bomb-rest-smoke.mjs
const API = 'http://localhost:8080';

let failures = 0;
let checks = 0;
function check(name, cond, detail = '') {
  checks++;
  if (cond) console.log(`  ✓ ${name}${detail ? ' | ' + detail : ''}`);
  else { failures++; console.log(`  ✕ ${name}${detail ? ' | ' + detail : ''}`); }
}
async function post(path, body) {
  const r = await fetch(API + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  return r.json();
}

// 1) 非法区间被拒
const bad = await post('/api/game/bomb/start', { min: 5, max: 6 });
check('非法区间被拒', bad && bad.code !== 0, JSON.stringify(bad));

// 2) 默认区间二分剪线 → DEFUSED（上限 = ceil(log2(99)) + 阻碍半补偿 = 10，命中全部阻碍才会 BOOM）
let defused = false;
for (let round = 0; round < 5 && !defused; round++) {
  const s = await post('/api/game/bomb/start', { min: 1, max: 100 });
  check('开局成功', s && s.code === 0 && !!s.data.gameId, JSON.stringify(s));
  if (!s || s.code !== 0) { console.log(`\n===== 拆弹REST结果: ${checks - failures}/${checks} 通过 =====`); process.exit(1); }
  const { gameId, low: lo, high: hi, maxCuts } = s.data;
  check('上限=ceil(log2(99))+半补偿=10', maxCuts === 10, String(maxCuts));
  check('默认区间 1~100', lo === 1 && hi === 100, `${lo}~${hi}`);
  check('威胁计数已下发', s.data.decoyCount === 4 && s.data.fuseCount === 2, `干扰线=${s.data.decoyCount} 熔断线=${s.data.fuseCount}`);

  let low = lo, high = hi, result = null, lastAttempts = 0;
  for (let i = 0; i < 20 && !result; i++) {
    const g = Math.floor((low + high) / 2);
    const r = await post('/api/game/bomb/guess', { gameId, number: g });
    if (r.code !== 0) { check(`第${i + 1}剪成功`, false, JSON.stringify(r)); break; }
    const d = r.data;
    check(`第${i + 1}剪 ${g} -> ${d.result}`, ['SAFE', 'DECOY', 'FUSE', 'DEFUSED', 'BOOM'].includes(d.result),
      `区间${d.low}~${d.high} 剩${d.cutsLeft}次`);
    check('attempts 单调推进', d.attempts >= lastAttempts + 1, `${lastAttempts} -> ${d.attempts}`);
    lastAttempts = d.attempts;
    if (d.result === 'SAFE' || d.result === 'FUSE') {
      check('SAFE/FUSE 单边收窄', d.low === low || d.high === high, `${low}~${high} -> ${d.low}~${d.high}`);
      low = d.low; high = d.high;
      check('剩余次数正确', d.cutsLeft === maxCuts - d.attempts, `${d.cutsLeft} vs ${maxCuts - d.attempts}`);
      if (d.result === 'FUSE') check('FUSE 公开该线', d.fuse === g, `fuse=${d.fuse}`);
    } else if (d.result === 'DECOY') {
      check('DECOY 区间不变', d.low === low && d.high === high, `${low}~${high} -> ${d.low}~${d.high}`);
      check('DECOY 公开该线', d.decoy === g, `decoy=${d.decoy}`);
      check('DECOY 消耗 1 次', d.cutsLeft === maxCuts - d.attempts, `${d.cutsLeft} vs ${maxCuts - d.attempts}`);
    } else {
      result = d.result;
      if (result === 'DEFUSED') {
        defused = true;
        check('DEFUSED 揭晓密码', d.code === g, `code=${d.code}`);
        const after = await post('/api/game/bomb/guess', { gameId, number: g });
        check('拆弹后对局已清除', after && after.code !== 0, JSON.stringify(after));
      } else {
        check('BOOM 揭晓密码', typeof d.code === 'number', `code=${d.code}`);
        check('BOOM 剩余 0 次', d.cutsLeft === 0, String(d.cutsLeft));
      }
    }
  }
  if (!defused) console.log(`  [!] 第 ${round + 1} 局二分遇阻碍耗尽（BOOM），重开验证`);
}
check('二分仍可拆弹（阻碍半补偿内）', defused);

// 3) 边界线号被拒（开区间，low/high 不可剪）
const s2 = await post('/api/game/bomb/start', { min: 1, max: 100 });
const g2 = s2.data.gameId;
const edge = await post('/api/game/bomb/guess', { gameId: g2, number: 1 });
check('剪 low 被拒', edge && edge.code !== 0, JSON.stringify(edge));
const edge2 = await post('/api/game/bomb/guess', { gameId: g2, number: 100 });
check('剪 high 被拒', edge2 && edge2.code !== 0, JSON.stringify(edge2));

// 4) 阻碍机制：小范围高密度（1..6，4 根线里 3 根是阻碍），线性扫描必遇阻碍
//    （极小概率前几剪直接 DEFUSED 或未遇全阻碍，重试至多 8 局）
let seenDecoy = false, seenFuse = false;
for (let round = 0; round < 8 && !(seenDecoy && seenFuse); round++) {
  const st = await post('/api/game/bomb/start', { min: 1, max: 6 });
  const id = st.data.gameId;
  check('小范围上限=4', st.data.maxCuts === 4, String(st.data.maxCuts));
  check('小范围威胁 2干扰+1熔断', st.data.decoyCount === 2 && st.data.fuseCount === 1, `干扰线=${st.data.decoyCount} 熔断线=${st.data.fuseCount}`);
  let lo2 = st.data.low, res = null, prevAttempts = 0;
  for (let i = 0; i < st.data.maxCuts + 2; i++) {
    const n = lo2 + 1;
    const rr = await post('/api/game/bomb/guess', { gameId: id, number: n });
    if (rr.code !== 0) break;
    res = rr.data;
    if (res.result === 'DECOY') {
      seenDecoy = true;
      check('DECOY 区间不变', res.low === lo2, `${lo2} -> ${res.low}`);
      check('DECOY 公开该线', res.decoy === n, `decoy=${res.decoy}`);
    } else if (res.result === 'FUSE') {
      seenFuse = true;
      check('FUSE 收窄', res.low > lo2, `${lo2} -> ${res.low}`);
      check('FUSE 公开该线', res.fuse === n, `fuse=${res.fuse}`);
      check('FUSE 额外消耗（attempts 跳 2）', res.attempts === prevAttempts + 2, `${prevAttempts} -> ${res.attempts}`);
    }
    prevAttempts = res.attempts;
    if (res.result === 'DEFUSED' || res.result === 'BOOM') {
      if (res.result === 'DEFUSED') check('小范围 DEFUSED 密码一致', res.code === n, `code=${res.code}`);
      else {
        check('小范围 BOOM 揭晓密码', typeof res.code === 'number', `code=${res.code}`);
        if (res.decoy) seenDecoy = true;   // 干扰线引发的 BOOM 也算观察到 DECOY
        if (res.fuse) seenFuse = true;
      }
      break;
    }
    lo2 = res.low;
  }
}
check('观察到 DECOY 阻碍', seenDecoy);
check('观察到 FUSE 阻碍', seenFuse);

// 5) 连续剪 low+1 耗尽次数 → BOOM（密码恰好极小则提前 DEFUSED，重试至多 3 局）
let boomed = false;
for (let round = 0; round < 3 && !boomed; round++) {
  const st = await post('/api/game/bomb/start', { min: 1, max: 100 });
  const id = st.data.gameId;
  let lo2 = st.data.low, res = null;
  for (let i = 0; i < st.data.maxCuts + 4; i++) {   // 干扰线会让 low 停滞，多给余量
    const n = lo2 + 1;
    const rr = await post('/api/game/bomb/guess', { gameId: id, number: n });
    if (rr.code !== 0) break;
    res = rr.data;
    if (res.result === 'DEFUSED' || res.result === 'BOOM') break;
    lo2 = res.low;
  }
  if (res && res.result === 'BOOM') {
    boomed = true;
    check('BOOM 揭晓密码', typeof res.code === 'number', `code=${res.code}`);
    check('BOOM 剩余 0 次', res.cutsLeft === 0, String(res.cutsLeft));
    const gone = await post('/api/game/bomb/guess', { gameId: id, number: 50 });
    check('BOOM 后对局已清除', gone && gone.code !== 0, JSON.stringify(gone));
  }
}
if (!boomed) console.log('  [!] 三次均提前 DEFUSED（密码恰好极小），BOOM 路径未触发');

// 6) 难度档位：easy 无阻碍，hard 密度翻倍，非法难度回落 normal
const se = await post('/api/game/bomb/start', { min: 1, max: 100, difficulty: 'easy' });
check('easy 无阻碍线', se && se.code === 0 && se.data.decoyCount === 0 && se.data.fuseCount === 0, `干扰线=${se && se.data.decoyCount} 熔断线=${se && se.data.fuseCount}`);
check('easy 上限=7', se && se.data.maxCuts === 7, String(se && se.data.maxCuts));
check('easy 难度回显', se && se.data.difficulty === 'easy', se && se.data.difficulty);

const sh = await post('/api/game/bomb/start', { min: 1, max: 100, difficulty: 'hard' });
check('hard 阻碍翻倍', sh && sh.code === 0 && sh.data.decoyCount === 9 && sh.data.fuseCount === 4, `干扰线=${sh && sh.data.decoyCount} 熔断线=${sh && sh.data.fuseCount}`);
check('hard 上限=14', sh && sh.data.maxCuts === 14, String(sh && sh.data.maxCuts));
check('hard 难度回显', sh && sh.data.difficulty === 'hard', sh && sh.data.difficulty);

const sx = await post('/api/game/bomb/start', { min: 1, max: 100, difficulty: 'nightmare' });
check('非法难度回落 normal', sx && sx.code === 0 && sx.data.difficulty === 'normal' && sx.data.decoyCount === 4 && sx.data.fuseCount === 2 && sx.data.maxCuts === 10,
  `难度=${sx && sx.data.difficulty} 干扰线=${sx && sx.data.decoyCount} 熔断线=${sx && sx.data.fuseCount} 上限=${sx && sx.data.maxCuts}`);

const sd = await post('/api/game/bomb/start', { min: 1, max: 100 });
check('缺省难度 normal', sd && sd.data.difficulty === 'normal', sd && sd.data.difficulty);

console.log(`\n===== 拆弹REST结果: ${checks - failures}/${checks} 通过 =====`);
process.exit(failures ? 1 : 0);
