const { Rcon } = require('./rcon');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const DAYS = Number(process.env.DAYS || 5);
const VILLAGERS = Number(process.env.VILLAGERS || 32);
const STAGE = process.env.STAGE || 'full';

function parse(status) {
  const m = status.match(/村民=(\d+) 已睡=(\d+) 有床=(\d+) 无床=(\d+) 离家远=(\d+)/);
  if (!m) return null;
  return { total: +m[1], sleeping: +m[2], withHome: +m[3], noHome: +m[4], far: +m[5] };
}

function parseInfo(info) {
  const m = info.match(/电梯井=(\d+) 路线缓存=(\d+) 床位分配=(\d+).*电梯乘坐=(\d+) 兜底传送=(\d+)/);
  if (!m) return null;
  return { shafts: +m[1], routes: +m[2], homes: +m[3], rides: +m[4], emergency: +m[5] };
}

(async () => {
  const rcon = new Rcon();
  await rcon.connect(30000);
  const run = async (c) => (await rcon.cmd(c, 30000)).trim();

  if (STAGE === 'full') {
    await run('gamerule doDaylightCycle false');
    await run('gamerule doMobSpawning false');
    await run('gamerule randomTickSpeed 0');
    await run('gamerule maxEntityCramming 0');
    await run('forceload add -16 -16 16 16');
    console.log(await run(`city tower 0 0 0 ${VILLAGERS}`));
    console.log(await run('city info'));
  }

  let pass = 0;
  for (let day = 1; day <= DAYS; day++) {
    // 白天：把所有人送回一楼，逼它们每晚重新走电梯
    await run('time set day');
    await sleep(3000);
    await run(`tp @e[type=minecraft:villager] 0.5 1 0.5`);
    await sleep(3000);
    const before = parse(await run('city status'));
    console.log(`day ${day} morning: ` + JSON.stringify(before));

    await run('time set night');
    let status = '';
    let ok = false;
    for (let i = 0; i < 18; i++) {
      await sleep(5000);
      status = await run('city status');
      const s = parse(status);
      if (s && s.total >= VILLAGERS && s.sleeping >= VILLAGERS) {
        ok = true;
        break;
      }
    }
    console.log(`day ${day}: ${ok ? 'PASS' : 'FAIL'} | ${status}`);
    if (ok) pass++;
  }

  const info = await run('city info');
  console.log('INFO ' + info);
  const p = parseInfo(info);
  console.log(`RESULT ${pass}/${DAYS} rides=${p ? p.rides : '?'} emergency=${p ? p.emergency : '?'}`);
  rcon.close();
  process.exit(pass === DAYS ? 0 : 1);
})().catch((e) => {
  console.error('ERR', e.message);
  process.exit(1);
});
