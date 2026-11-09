// UI tests for the remote page and setup page, in headless Chromium with a fake app bridge.
// Usage: node ui-test.js [path to the v1.2 panel.html for a side-by-side check]
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const A = path.join(__dirname, '../app/src/main/assets');
const OUT = path.join(__dirname, 'work/shots'); fs.mkdirSync(OUT, { recursive: true });
let fails = 0;
const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) fails++; };

// The fake bridge the Android app normally provides as window.Remote
function bridgeScript(layoutFile, brand, supported) {
  const layouts = {};
  for (const f of fs.readdirSync(path.join(A, 'layouts'))) layouts[f.replace('.json', '')] = fs.readFileSync(path.join(A, 'layouts', f), 'utf8');
  return `(() => {
    const L=${JSON.stringify(layouts)};let cur=${JSON.stringify(layoutFile)},mine=null;
    window.calls=[];const log=(...a)=>window.calls.push(a);
    window.Remote={press:(b,d)=>log('press',b,d),launch:r=>log('launch',r),text:t=>log('text',t),haptic(){},voice:o=>log('voice',o),hide:()=>log('hide'),
      setup:()=>log('setup'),reconnect:()=>log('reconnect'),focusable(){},brand:()=>${JSON.stringify(brand)},state:()=>'{"state":"connected"}',
      supported:()=>${JSON.stringify(JSON.stringify(supported))},apps:()=>setTimeout(()=>onApps([{name:'Netflix',ref:'12'},{name:'Stan',ref:'51207'}]),50),
      layout:()=>cur==='mine'?mine:L[cur],saveLayout:j=>{mine=j;cur='mine';log('save',j)},useLayout:id=>{cur=id;return id==='mine'?mine:L[id]},
      layouts:()=>JSON.stringify([...(mine?[{id:'mine',name:'My layout',current:cur==='mine'}]:[]),...Object.keys(L).map(k=>({id:k,name:JSON.parse(L[k]).name,current:k===cur}))])};
  })();`;
}
const GOOGLE = ['POWER','MUTE','VOL_UP','VOL_DOWN','CH_UP','CH_DOWN','INPUT','USB','HDMI1','HDMI2','HDMI3','HDMI4','LIVE_TV','UP','DOWN','LEFT','RIGHT','OK','BACK','HOME','SETTINGS','PROFILE','GUIDE','INFO','MENU','APPS','PLAY_PAUSE','PLAY','PAUSE','STOP','REWIND','FFWD','PREV','NEXT','SEARCH','CAPTIONS','RED','GREEN','YELLOW','BLUE','NUM_0','NUM_1','DEL','ENTER'];
const ROKU = ['POWER','MUTE','VOL_UP','VOL_DOWN','CH_UP','CH_DOWN','LIVE_TV','HDMI1','HDMI2','HDMI3','HDMI4','INPUT','UP','DOWN','LEFT','RIGHT','OK','BACK','HOME','INFO','SETTINGS','PLAY_PAUSE','PLAY','PAUSE','REWIND','FFWD','REPLAY','SEARCH','NUM_0','DEL','ENTER','FIND_REMOTE'];
// The panel on the Fold6: 486 x 1884 real pixels = 300 x 1163 CSS px
const PANEL = { viewport: { width: 300, height: 1163 }, deviceScaleFactor: 1.62, hasTouch: true };

(async () => {
  const browser = await chromium.launch();
  const page = async (opts, init) => { const c = await browser.newContext(opts); const p = await c.newPage(); p.on('pageerror', e => { console.log('PAGE ERROR', e.message); fails++; }); if (init) await p.addInitScript(init); return p; };

  // ---------- 1. Alex's layout looks like v1.2 ----------
  const p = await page(PANEL, bridgeScript('alex', 'googletv', GOOGLE));
  await p.goto('file://' + A + '/panel.html');
  await p.screenshot({ path: OUT + '/alex-layout.png' });
  const old = process.argv[2];
  if (old) {
    const o = await page(PANEL, `window.Remote={key(){},launch(){},haptic(){},voice(){},text(){},hide(){},setup(){},reconnect(){},focusable(){},appIcon(){return ''},state(){return '{"state":"connected"}'}}`);
    await o.goto('file://' + old);
    await o.screenshot({ path: OUT + '/v1.2-panel.png' });
    // same positions for every button? compare centres of the round keys
    const centres = async (pg, sel) => pg.$$eval(sel, els => els.map(e => { const r = e.getBoundingClientRect(); return [Math.round(r.x + r.width / 2), Math.round(r.y + r.height / 2)]; }));
    const a = await centres(o, '.key, .rocker, .pill, .pad'), b = await centres(p, '.key, .rocker, .pill, .pad');
    const same = a.length === b.length && a.every((x, i) => Math.abs(x[0] - b[i][0]) <= 1 && Math.abs(x[1] - b[i][1]) <= 1);
    ok(same, `Alex's layout puts all ${b.length} buttons in the same places as v1.2` + (same ? '' : ' ' + JSON.stringify([a, b])));
  }

  // ---------- 2. buttons send the right generic names ----------
  const tap = async (sel) => { const r = await p.locator(sel).first().boundingBox(); await p.mouse.move(r.x + r.width / 2, r.y + r.height / 2); await p.mouse.down(); await p.mouse.up(); };
  const calls = async () => { const c = await p.evaluate(() => window.calls.slice()); await p.evaluate(() => window.calls.length = 0); return c; };
  await tap('[data-b=POWER]'); await tap('[data-b=USB]'); await tap('[data-b=VOL_UP]'); await tap('[data-b=HOME]');
  let c = await calls();
  ok(JSON.stringify(c) === JSON.stringify([['press','POWER',3],['press','USB',3],['press','VOL_UP',3],['press','HOME',3]]), 'Tapping Power, USB, Vol+, Home sends POWER, USB, VOL_UP, HOME: ' + JSON.stringify(c));
  // hold = long press
  let r = await p.locator('[data-b=BACK]').boundingBox();
  await p.mouse.move(r.x + 35, r.y + 35); await p.mouse.down(); await p.waitForTimeout(700); await p.mouse.up();
  c = await calls(); ok(JSON.stringify(c) === JSON.stringify([['press','BACK',1],['press','BACK',2]]), 'Holding Back sends a long press: ' + JSON.stringify(c));
  // hold volume = repeat
  r = await p.locator('[data-b=VOL_DOWN]').boundingBox();
  await p.mouse.move(r.x + 35, r.y + 35); await p.mouse.down(); await p.waitForTimeout(900); await p.mouse.up();
  c = await calls(); ok(c.length >= 4 && c.every(x => x[1] === 'VOL_DOWN'), `Holding Vol− repeats (${c.length} presses)`);
  // touchpad: tap = OK, swipe = arrows
  r = await p.locator('#pad').boundingBox();
  const cx = r.x + r.width / 2, cy = r.y + r.height / 2;
  await p.mouse.move(cx, cy); await p.mouse.down(); await p.mouse.up();
  await p.mouse.move(cx, cy); await p.mouse.down(); await p.mouse.move(cx + 80, cy, { steps: 8 }); await p.mouse.up();
  await p.mouse.move(cx, cy); await p.mouse.down(); await p.mouse.move(cx, cy - 40, { steps: 5 }); await p.mouse.up();
  c = await calls(); ok(JSON.stringify(c) === JSON.stringify([['press','OK',3],['press','RIGHT',3],['press','RIGHT',3],['press','UP',3]]), 'Touchpad tap/swipe right/swipe up: ' + JSON.stringify(c));
  // app button: tap = app, hold = other version
  await tap('.pill.st');
  r = await p.locator('.pill.st').boundingBox();
  await p.mouse.move(r.x + 50, r.y + 20); await p.mouse.down(); await p.waitForTimeout(800); await p.mouse.up();
  c = await calls(); ok(c[0][1] === 'market://launch?id=com.teamsmart.videomanager.tv' && c[1][1] === 'market://launch?id=com.liskovsoft.smarttubetv.beta', 'SmartTube tap and hold open the two versions');
  // mic
  await tap('#mic'); c = await calls(); ok(JSON.stringify(c) === '[["voice",true]]', 'Mic starts voice');
  // typing
  await p.evaluate(() => onText('Search')); await p.fill('#typeIn', 'bluey'); await p.click('#tSend');
  c = await calls(); ok(JSON.stringify(c) === '[["text","bluey"],["press","ENTER",3]]', 'Typing sends text then Enter');

  // ---------- 3. editor: ✕ remove, + add, hold to move, save as JSON ----------
  await tap('#grip'); await p.click('[data-m=edit]');
  ok(await p.locator('.remote.editing').count() === 1, 'Edit mode opens from the menu');
  await p.screenshot({ path: OUT + '/editor.png' });
  // remove Profile
  const prof = p.locator('.key[aria-label=Profile] .x'); r = await prof.boundingBox();
  await p.mouse.move(r.x + 10, r.y + 10); await p.mouse.down(); await p.mouse.up();
  ok(await p.locator('.key[aria-label=Profile]').count() === 0, '✕ removes Profile');
  // add Guide to the last row
  const adds = p.locator('.keys .add'); r = await adds.last().boundingBox();
  await p.mouse.move(r.x + 30, r.y + 30); await p.mouse.down(); await p.mouse.up();
  ok(await p.locator('#picker:not([hidden])').count() === 1, '+ opens the button list');
  await p.screenshot({ path: OUT + '/editor-add.png' });
  await p.click('#pickBody [data-k=GUIDE]');
  ok(await p.locator('.key[aria-label=Guide]').count() === 1, '+ adds Guide');
  // add an app to the app row (asks the TV for apps)
  r = await p.locator('.pills .add').boundingBox(); await p.mouse.move(r.x + 30, r.y + 20); await p.mouse.down(); await p.mouse.up();
  await p.waitForSelector('#pickBody [data-ref="51207"]'); await p.click('#pickBody [data-ref="51207"]');
  ok(await p.locator('.pill[aria-label=Stan]').count() === 1, '+ adds the Stan app from the TV’s list');
  // hold Mute and drag it onto Power's right side → order becomes grip, POWER, MUTE
  const mute = await p.locator('.key[aria-label=Mute]').boundingBox(), power = await p.locator('.key[aria-label=Power]').boundingBox();
  await p.mouse.move(mute.x + 35, mute.y + 35); await p.mouse.down(); await p.waitForTimeout(450);
  await p.mouse.move(power.x + 55, power.y + 35, { steps: 12 }); await p.screenshot({ path: OUT + '/editor-drag.png' }); await p.mouse.up();
  await p.click('#eDone');
  c = await calls(); const saved = c.find(x => x[0] === 'save');
  ok(!!saved, 'Done saves the layout');
  const L = JSON.parse(saved[1]);
  ok(JSON.stringify(L.rows[0].items) === '["grip","POWER","MUTE"]', 'Hold-and-drag moved Mute after Power: ' + JSON.stringify(L.rows[0].items));
  ok(!L.rows[2].items.includes('PROFILE') && L.rows[4].items.includes('GUIDE') && L.rows[1].items.some(a => a.name === 'Stan' && a.googletv === '51207'), 'Saved JSON has Profile removed, Guide and Stan added');
  await p.screenshot({ path: OUT + '/my-layout.png' });
  // layouts picker lists presets + My layout, and switching works
  await tap('#grip'); await p.click('[data-m=layouts]');
  const names = await p.$$eval('#layoutList .item', els => els.map(e => e.textContent));
  ok(names.join('|') === "My layout ✓|Alex's layout|Google TV|Roku", 'Layouts list: ' + names.join(' | '));
  await p.click('#layoutList [data-l=roku]');
  ok(await p.locator('.key[aria-label="Replay"]').count() === 1, 'Switching to the Roku preset shows its buttons');

  // ---------- 4. Roku preset on a Roku ----------
  const k = await page(PANEL, bridgeScript('roku', 'roku', ROKU));
  await k.goto('file://' + A + '/panel.html');
  await k.screenshot({ path: OUT + '/roku-layout.png' });
  const dim = await k.$$eval('.key', els => els.filter(e => e.style.opacity === '0.35').length);
  ok(dim === 0, 'Every button in the Roku preset is one a Roku understands');
  r = await k.locator('.pill').first().boundingBox(); await k.mouse.move(r.x + 40, r.y + 20); await k.mouse.down(); await k.mouse.up();
  c = await k.evaluate(() => window.calls); ok(JSON.stringify(c) === '[["launch","12"]]', 'Netflix on Roku launches channel 12');

  // ---------- 5. no TV yet / can't reach ----------
  await k.evaluate(() => onState('offline', 'Can’t reach the Roku. Is it on, and on the same Wi-Fi?'));
  ok(await k.locator('#banner:not([hidden])').count() === 1, 'Offline banner shows over the touchpad');
  await k.screenshot({ path: OUT + '/roku-offline.png' });

  // ---------- 6. Chromebook window mode ----------
  const w = await page({ viewport: { width: 420, height: 900 } }, bridgeScript('alex', 'googletv', GOOGLE) + ';window.Remote.mode=()=>"window";');
  await w.goto('file://' + A + '/panel.html');
  await w.screenshot({ path: OUT + '/window-mode.png' });
  ok(await w.evaluate(() => document.documentElement.classList.contains('win')), 'Window mode scales the remote');

  // ---------- 7. setup screen: search results, pairing, photo ----------
  const s = await page({ viewport: { width: 412, height: 1500 }, deviceScaleFactor: 2 }, `(() => {
    window.scalls=[];const log=(...a)=>window.scalls.push(a);
    window.Setup={status:()=>JSON.stringify({overlay:true,mic:true,notif:false,tvs:[],current:'',running:false}),askOverlay(){},askMic(){},askNotif(){},
      find:()=>log('find'),stopFind(){},probe:h=>log('probe',h),pair:(b,h,n)=>log('pair',b,h,n),code:c=>log('code',c),cancelPair(){},use(){},forget(){},
      photo:()=>log('photo'),pickPhoto(){},show(){},window(){},chromebook:()=>false,stop(){},resetSize(){},addTile(){},licences(){}};
  })();`);
  await s.goto('file://' + A + '/setup.html');
  ok(await s.evaluate(() => scalls[0][0] === 'find'), 'Setup starts searching straight away');
  await s.evaluate(() => {
    onFound({host:'192.168.1.23',brand:'googletv',name:'Kogan G6',brandName:'Google TV',supported:true});
    onFound({host:'192.168.1.30',brand:'roku',name:'Lounge Roku TV',brandName:'Roku',supported:true});
    onFound({host:'192.168.1.41',brand:'samsung',name:'[TV] Samsung Q60',brandName:'Samsung',supported:false});
    onFound({host:'192.168.1.23',brand:'other',name:'Kogan',brandName:'Other',supported:false}); // same TV via DIAL: Google TV wins
  });
  const cards = await s.$$eval('#tvs .tv', els => els.map(e => e.textContent));
  ok(cards.length === 3 && cards[0].includes('Kogan G6') && cards[2].includes('Coming in a later version'), 'Found TVs listed, one card per TV, unsupported last');
  await s.screenshot({ path: OUT + '/setup-found.png' });
  await s.click('#tvs [data-h="192.168.1.30"]');
  ok(JSON.stringify(await s.evaluate(() => scalls.at(-1))) === '["pair","roku","192.168.1.30","Lounge Roku TV"]', 'Tapping the Roku connects straight away (no code)');
  await s.click('#tvs [data-h="192.168.1.23"]');
  await s.evaluate(() => onPair('code', ''));
  ok(await s.locator('#codeBox:not([hidden])').count() === 1, 'Tapping the Google TV asks for the code');
  await s.fill('#code', 'a1b2c3');
  ok(JSON.stringify(await s.evaluate(() => scalls.at(-1))) === '["code","A1B2C3"]', 'Code auto-submits at 6 characters, in capitals');
  await s.screenshot({ path: OUT + '/setup-code.png' });
  await s.click('#bCancel');
  await s.evaluate(() => onPhoto({platform:'roku',label:'TCL Roku TV',ip:'192.168.1.30',supported:true,brandName:'Roku'}, ''));
  ok(JSON.stringify(await s.evaluate(() => scalls.at(-1))) === '["probe","192.168.1.30"]', 'Photo with an IP connects to that IP');
  await s.evaluate(() => onPhoto({platform:'samsung',label:'Samsung',ip:'',supported:false,brandName:'Samsung'}, ''));
  ok((await s.textContent('#photoMsg')).includes('coming in a later version'), 'Photo of a Samsung says it is coming later');
  await s.screenshot({ path: OUT + '/setup-photo.png' });

  await browser.close();
  console.log(fails === 0 ? 'ALL PASS' : fails + ' FAILED');
  process.exit(fails ? 1 : 0);
})();
