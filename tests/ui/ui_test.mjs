// 기내톡 화면 테스트: 실제 브라우저(Edge, 헤드리스)로 휴대폰 3대를 흉내 냅니다.
//   node ui_test.mjs python     (dist/skytalk.py)
//   node ui_test.mjs windows    (dist/SkyTalk-Windows.bat 의 PowerShell 본문)
import { chromium } from 'playwright';
import jsQR from 'jsqr';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const DIST = path.join(ROOT, 'dist');
const kind = process.argv[2] || 'python';
const SHOTS = path.join(HERE, 'shots', kind);
fs.mkdirSync(SHOTS, { recursive: true });
const fails = [];
const check = (c, what) => { console.log((c ? '  ok   ' : '  FAIL ') + what); if (!c) fails.push(what); };
const sleep = ms => new Promise(r => setTimeout(r, ms));

const port = kind === 'python' ? 18380 : 18480;
const data = fs.mkdtempSync(path.join(os.tmpdir(), 'skytalk-ui-'));
let server = null, base = '';

function startServer() {
  let cmd, args;
  if (kind === 'python') {
    cmd = 'python';
    args = [path.join(DIST, 'skytalk.py'), '--port', String(port), '--data', data, '--wifi-name', 'SkyTalk', '--wifi-pass', 'skytalk1234'];
    base = `http://127.0.0.1:${port}`;
  } else {
    const bat = path.join(DIST, 'SkyTalk-Windows.bat').replace(/'/g, "''");
    const dq = data.replace(/'/g, "''");
    cmd = 'powershell';
    args = ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
      `$s=[IO.File]::ReadAllText('${bat}',[Text.Encoding]::UTF8); & ([ScriptBlock]::Create($s)) -Port ${port} -DataDir '${dq}' -NoHotspot -NoFirewall -NoBrowser`];
    base = `http://localhost:${port}`;
  }
  server = spawn(cmd, args, { stdio: 'ignore', windowsHide: true });
}
async function waitUp() {
  for (let i = 0; i < 120; i++) {
    try { const r = await fetch(base + '/api/info'); if (r.ok) return true; } catch (e) { /* not yet */ }
    await sleep(300);
  }
  return false;
}
function stopServer() { return new Promise(res => { if (!server || server.exitCode !== null) return res(); server.once('exit', res); server.kill(); }); }

const phone = (w, h) => ({ viewport: { width: w, height: h }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'ko-KR', timezoneId: 'Asia/Seoul' });

async function join(page, name, seat) {
  await page.goto(base + '/');
  await page.waitForSelector('#dlgProfile:not([hidden])', { timeout: 8000 });
  await page.fill('#pfName', name);
  await page.fill('#pfSeat', seat);
  await page.click('#pfForm button[type=submit]');
  await page.waitForSelector('#dlgProfile', { state: 'hidden' });
}
async function say(page, text) {
  await page.fill('#input', text);
  await page.click('#btnSend');
  await page.waitForFunction(t => Array.from(document.querySelectorAll('#msgs .row.me .bubble')).some(b => b.textContent === t), text, { timeout: 8000 });
}
const seen = (page, text) => page.waitForFunction(t => Array.from(document.querySelectorAll('#msgs .bubble')).some(b => b.textContent.includes(t)), text, { timeout: 8000 }).then(() => true, () => false);

async function decodeQr(page, text) {
  const m = await page.evaluate(t => window.SkyTalkQR.encode(t), text);
  if (!m) return { ok: false };
  const scale = 6, quiet = 4, n = m.length, size = (n + quiet * 2) * scale;
  const px = new Uint8ClampedArray(size * size * 4).fill(255);
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    if (!m[y][x]) continue;
    for (let dy = 0; dy < scale; dy++) for (let dx = 0; dx < scale; dx++) {
      const o = (((y + quiet) * scale + dy) * size + (x + quiet) * scale + dx) * 4;
      px[o] = px[o + 1] = px[o + 2] = 0;
    }
  }
  const r = jsQR(px, size, size);
  return { ok: !!r && r.data === text, version: (n - 17) / 4, got: r && r.data };
}

async function main() {
  startServer();
  if (!await waitUp()) throw new Error('server did not start');
  console.log(`== ${kind} server at ${base}`);
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const ctxA = await browser.newContext(phone(390, 844));
  const ctxB = await browser.newContext(phone(412, 915));
  const ctxC = await browser.newContext(phone(390, 844));
  const A = await ctxA.newPage(), B = await ctxB.newPage(), C = await ctxC.newPage();
  const errors = [];
  for (const [n, p] of [['A', A], ['B', B], ['C', C]]) {
    p.on('pageerror', e => errors.push(n + ': ' + e.message));
    p.on('console', msg => { if (msg.type() === 'error' && !/Failed to load resource|net::ERR/.test(msg.text())) errors.push(n + ' console: ' + msg.text()); });
  }

  // 첫 화면 (프로필)
  await A.goto(base + '/');
  await A.waitForSelector('#dlgProfile:not([hidden])');
  check(await A.isVisible('#pfName'), 'first visit asks for a name');
  await sleep(400);
  await A.screenshot({ path: path.join(SHOTS, '1-profile.png') });
  await A.fill('#pfName', '민수'); await A.fill('#pfSeat', '32a');
  await A.click('#pfForm button[type=submit]');
  await A.waitForSelector('#dlgProfile', { state: 'hidden' });
  check(true, 'A joined');
  await join(B, '지영', '41c');
  await join(C, '현우', '18f');

  // 대화
  await say(A, '다들 자리 어디예요? 저 32A요');
  check(await seen(B, '다들 자리 어디예요?'), 'B receives A\'s message');
  await say(B, '41C! 생각보다 멀다 ㅋㅋ');
  await say(C, '18F요. 앞쪽이라 기내식 먼저 나오는 듯 🍱');
  check(await seen(A, '기내식 먼저'), 'A receives C\'s message');
  await C.close();   // 현우는 잠들었다고 가정 (읽음 표시 확인용)
  await say(A, '착륙하면 수하물 찾는 곳 3번 벨트 앞에서 모여요');
  check(await seen(B, '3번 벨트'), 'B receives the meeting point');
  await say(B, '@민수 좋아요 👍');
  check(await A.waitForSelector('#msgs .bubble.mention', { timeout: 8000 }).then(() => true, () => false), 'A sees the @mention highlighted');

  // 사진
  const img = await B.evaluate(() => {
    const c = document.createElement('canvas'); c.width = 960; c.height = 640; const g = c.getContext('2d');
    const sky = g.createLinearGradient(0, 0, 0, 640); sky.addColorStop(0, '#5b7fb8'); sky.addColorStop(0.55, '#e9b48a'); sky.addColorStop(1, '#f3d9b8');
    g.fillStyle = sky; g.fillRect(0, 0, 960, 640);
    g.fillStyle = 'rgba(255,240,215,.95)'; g.beginPath(); g.arc(700, 380, 46, 0, Math.PI * 2); g.fill();
    g.fillStyle = 'rgba(255,255,255,.85)';
    for (const [x, y, s] of [[160, 430, 1], [420, 470, 1.4], [760, 500, 1.1], [300, 540, 1.6], [620, 560, 1.3]]) {
      for (const [dx, dy, r] of [[0, 0, 38], [40, -14, 44], [86, 0, 36], [44, 12, 40]]) { g.beginPath(); g.arc(x + dx * s, y + dy * s, r * s, 0, Math.PI * 2); g.fill(); }
    }
    g.fillStyle = '#2b3242'; g.beginPath(); g.moveTo(0, 640); g.lineTo(0, 560); g.lineTo(520, 640); g.fill();
    return c.toDataURL('image/png');
  });
  const imgPath = path.join(os.tmpdir(), 'skytalk-ui-sky.png');
  fs.writeFileSync(imgPath, Buffer.from(img.split(',')[1], 'base64'));
  await B.setInputFiles('#file', imgPath);
  const picOk = await A.waitForFunction(() => Array.from(document.querySelectorAll('#msgs .bubble.pic img')).some(i => i.complete && i.naturalWidth > 0), null, { timeout: 15000 }).then(() => true, () => false);
  check(picOk, 'photo from B shows up on A');
  const picW = await A.evaluate(() => { const i = document.querySelector('#msgs .bubble.pic img'); return i ? i.naturalWidth : 0; });
  check(picW > 0 && picW <= 1600, 'photo was resized before upload (' + picW + 'px wide)');
  await say(B, '창밖 구름 미쳤다');
  await say(A, '와 ㄷㄷ 사진 좋다');

  // 읽음 표시: B는 읽고, C(닫힘)는 안 읽음 → A의 마지막 메시지 옆에 1
  const unreadOk = await A.waitForFunction(() => {
    const rows = Array.from(document.querySelectorAll('#msgs .row.me'));
    const last = rows[rows.length - 1];
    return last && last.querySelector('.unread').textContent === '1';
  }, null, { timeout: 10000 }).then(() => true, () => false);
  check(unreadOk, 'read count shows 1 (one teammate has not read)');
  await sleep(600);
  await A.screenshot({ path: path.join(SHOTS, '2-chat-A.png') });
  await B.screenshot({ path: path.join(SHOTS, '3-chat-B.png') });

  // 참여자
  await A.click('#btnPeople');
  await A.waitForSelector('#dlgPeople:not([hidden])');
  const people = await A.$$eval('#peopleList li', lis => lis.length);
  check(people === 3, 'people list has 3 members (' + people + ')');
  check(/2|3/.test(await A.textContent('#onlineCount')), 'online count shown in header');
  await sleep(400);
  await A.screenshot({ path: path.join(SHOTS, '4-people.png') });
  await A.click('#dlgPeople [data-close]');

  // 초대 + QR
  await A.click('#btnInvite');
  await A.waitForSelector('#dlgInvite:not([hidden]) #invUrls b');
  const qrs = await A.$$eval('#invQrs svg', s => s.length);
  check(qrs === (kind === 'python' ? 2 : 1), 'invite shows QR codes (' + qrs + ')');
  if (kind === 'python') check((await A.textContent('#invWifi')).includes('SkyTalk'), 'invite shows Wi-Fi name');
  await sleep(400);
  await A.screenshot({ path: path.join(SHOTS, '5-invite.png') });
  await A.click('#dlgInvite [data-close]');
  for (const t of ['WIFI:T:WPA;S:SkyTalk;P:skytalk1234;;', 'http://192.168.137.1:8080/', 'http://192.168.49.1:8080/',
    '기내톡 테스트 ✈ 한글과 emoji 🚀', 'http://10.0.0.1:8080/?' + 'x'.repeat(120), 'A'.repeat(210)]) {
    const r = await decodeQr(A, t);
    check(r.ok, `QR decodes back exactly (v${r.version}, ${t.length} chars)`);
  }

  // 테마
  await A.click('#btnMenu'); await A.click('#mTheme'); await A.click('#dlgMenu [data-close]');
  check(await A.evaluate(() => document.documentElement.dataset.theme) === 'light', 'theme switches to light');
  await A.screenshot({ path: path.join(SHOTS, '6-light.png') });
  await A.click('#btnMenu'); await A.click('#mTheme'); await A.click('#dlgMenu [data-close]');

  // 대화 저장
  await A.click('#btnMenu');
  const [dl] = await Promise.all([A.waitForEvent('download'), A.click('#mExport')]);
  const txt = fs.readFileSync(await dl.path(), 'utf8');
  check(txt.includes('민수(32A): 다들 자리 어디예요?') && txt.includes('[사진]'), 'export file contains the conversation');
  await A.click('#dlgMenu [data-close]').catch(() => {});

  // 연결 끊김 → 보낸 메시지 대기 → 다시 연결되면 자동 전송
  await stopServer();
  const bannerOk = await A.waitForSelector('#banner:not([hidden])', { timeout: 15000 }).then(() => true, () => false);
  check(bannerOk, 'disconnect banner appears when the host goes away');
  await A.fill('#input', '연결 끊겼을 때 보낸 메시지');
  await A.click('#btnSend');
  check(await A.waitForSelector('#pend .row', { timeout: 3000 }).then(() => true, () => false), 'message waits in the outbox while offline');
  await sleep(4500);
  await A.screenshot({ path: path.join(SHOTS, '7-offline.png') });
  startServer();
  check(await waitUp(), 'host comes back');
  const delivered = await seen(B, '연결 끊겼을 때 보낸 메시지').then(ok => ok || seen(B, '연결 끊겼을 때 보낸 메시지'));
  check(delivered, 'queued message is delivered after reconnect');
  const cleared = await A.waitForFunction(() => document.querySelectorAll('#pend .row').length === 0 && document.querySelector('#banner').hidden, null, { timeout: 15000 }).then(() => true, () => false);
  check(cleared, 'outbox empties and banner hides after reconnect');

  // 새로 고침 후에도 대화가 바로 보임 (기기 저장)
  await B.reload();
  check(await seen(B, '다들 자리 어디예요?'), 'history is shown again after reload');

  // 노트북(호스트) 화면 크기
  const ctxD = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'ko-KR', timezoneId: 'Asia/Seoul' });
  const D = await ctxD.newPage();
  await D.goto(base + '/');
  await D.waitForSelector('#dlgProfile:not([hidden])');
  check(await D.evaluate(() => document.getElementById('app').inert === true), 'screen behind an open dialog is locked (inert)');
  await D.focus('#pfName');
  await D.keyboard.type('호스트');
  await D.keyboard.press('Enter');
  check(await D.evaluate(() => document.activeElement && document.activeElement.id === 'pfSeat'), 'Enter in the name field moves to the seat field');
  await D.keyboard.type('1a');
  await D.keyboard.press('Enter');
  check(await D.waitForSelector('#dlgProfile', { state: 'hidden', timeout: 4000 }).then(() => true, () => false), 'Enter in the seat field starts the chat');
  check(await D.evaluate(() => document.getElementById('app').inert === false), 'screen is usable again after the dialog closes');
  await sleep(800);
  await D.screenshot({ path: path.join(SHOTS, '8-desktop.png') });

  check(errors.length === 0, 'no page errors' + (errors.length ? ': ' + errors.slice(0, 3).join(' | ') : ''));
  await browser.close();
}

try { await main(); }
catch (e) { console.error(e); fails.push('exception: ' + e.message); }
finally { await stopServer(); fs.rmSync(data, { recursive: true, force: true }); }
console.log(`\nRESULT: ${fails.length ? 'FAIL' : 'PASS'} (${fails.length} failures)`);
fails.forEach(f => console.log('  - ' + f));
process.exit(fails.length ? 1 : 0);
