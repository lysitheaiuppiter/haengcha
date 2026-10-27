// 정차·주행 화면 그림을 만든다. three.js 무대(scene.html, stage.js)를 헤드리스 크롬(GPU)으로 찍는다.
// - 움직이는 배경(motion/bg_*.mp4): 고래·등대·반딧불·초롱이 움직이는 11초 루프 영상. 앱이 반복 재생한다
//   도감 지역 13곳(towns.js)은 지역 상징 모형이 선 장면 하나씩
// - 정지 배경(scenes/bg_*.webp): 영상이 뜨기 전·재생 실패 때 대신 쓴다
// - 탈것 레이어(scenes/vehicle_<테마>.webp, 투명): 배경 위에 겹친다
// - 주행 화면 탈것 레이어(scenes/vehicle_<테마>_rear.webp, 투명): 단상 없이 화면 안쪽으로 달려가는 뒷모습
// - 도감·해금 연출용 탈것 아이콘(icons/icon_<테마>.webp, 투명 정사각)
// - 앱 실행 아이콘(res/mipmap-*/ic_launcher.png)과 제출용 512px 아이콘(docs/submission/app_icon_512.png)
// 모델은 assets3d/models/*.glb(tools/models/joseon_set.py, region_set.py로 만든 Blender 모델)를 쓴다.
// 준비: cd tools/render3d && npm install
// 예)  node tools/render3d/render.mjs
//      node tools/render3d/render.mjs --only motion_bg_ulsan_0 vehicle_rickshaw
import http from 'http';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { execFileSync, spawn } from 'child_process';
import { fileURLToPath } from 'url';
import puppeteer from 'puppeteer-core';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const assets = path.join(repo, 'app', 'src', 'main', 'assets');
const THEMES = ['ox_cart', 'rickshaw', 'gama', 'kkotgama', 'eoga'];
const GRADES = [0, 1, 2, 3, 4];
// 도감 지역(towns.js)은 단골 등급과 상관없이 장면이 하나다
const TOWNS = ['seoul', 'busan', 'daegu', 'incheon', 'gwangju', 'daejeon', 'gyeongju', 'jeonju', 'gangneung', 'jeju', 'yeosu', 'andong', 'cheongju'];
const SCENES = [
  { region: null, grade: 0, name: 'bg_palace' },
  ...['ulsan', 'hoengseong'].flatMap((region) => GRADES.map((grade) => ({ region, grade, name: `bg_${region}_${grade}` }))),
  ...TOWNS.map((region) => ({ region, grade: 0, name: `bg_${region}` })),
];
const JOBS = [
  ...THEMES.map((name) => ({ kind: 'vehicle', name, file: `vehicle_${name}` })),
  ...THEMES.map((name) => ({ kind: 'rear', name, file: `vehicle_${name}_rear` })),
  ...SCENES.map((s) => ({ kind: 'background', ...s, file: s.name })),
  ...SCENES.map((s) => ({ kind: 'motion', ...s, file: `motion_${s.name}` })),
  ...THEMES.map((name) => ({ kind: 'icon', name, file: `icon_${name}` })),
  { kind: 'appicon', file: 'app_icon' },
];
const MIPMAP = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
// Pleos 앱 영역 크기(landscape 두 칸, portrait 한 칸)에 맞춘다. 영상은 같은 비율로 줄인다
const LAYOUTS = { landscape: [1700, 1212], portrait: [840, 1212] };
const MOTION = { landscape: [1280, 912], portrait: [640, 924] };
const FPS = 24;
const ICON_SIZE = 512;

const argv = process.argv.slice(2);
const opt = (k, d) => (argv.includes(k) ? argv[argv.indexOf(k) + 1] : d);
const out = path.resolve(opt('--out', assets));
const chrome = opt('--chrome', 'C:/Program Files/Google/Chrome/Application/chrome.exe');
const only = argv.includes('--only') ? argv.slice(argv.indexOf('--only') + 1).filter((a) => !a.startsWith('--')) : null;
const jobs = only ? JOBS.filter((j) => only.some((o) => j.file.startsWith(o))) : JOBS;

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.glb': 'model/gltf-binary' };
const server = http.createServer((req, res) => {
  const p = decodeURIComponent(req.url.split('?')[0]);
  const file = p.startsWith('/models/') ? path.join(repo, 'assets3d', p) : path.join(here, p);
  if (!file.startsWith(repo) || !fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
}).listen(0, '127.0.0.1');
await new Promise((r) => server.once('listening', r));

// 호스트 GPU(Windows D3D11)로 그린다. 프레임 수백 장을 뽑아야 해서 소프트웨어 렌더러로는 느리다
const browser = await puppeteer.launch({
  executablePath: chrome,
  headless: true,
  args: ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'],
});
const page = await browser.newPage();
page.on('pageerror', (e) => console.error('page error:', e.message));
await page.goto(`http://127.0.0.1:${server.address().port}/scene.html`);

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'haengcha-scene-'));
const dir = (sub) => { const d = path.join(out, sub); fs.mkdirSync(d, { recursive: true }); return d; };
const png = (data) => Buffer.from(data.split(',')[1], 'base64');
const webp = (buf, file, alpha, quality) => {
  const src = path.join(tmp, 'frame.png');
  fs.writeFileSync(src, buf);
  execFileSync('ffmpeg', ['-v', 'error', '-y', '-i', src, '-c:v', 'libwebp', ...(alpha ? ['-pix_fmt', 'yuva420p'] : []), '-quality', String(quality), file]);
  console.log(`${path.relative(out, file)} ${(fs.statSync(file).size / 1024).toFixed(0)}KB`);
};

for (const job of jobs) {
  if (job.kind === 'appicon') {
    const src = path.join(tmp, 'app_icon.png');
    fs.writeFileSync(src, png(await page.evaluate((o) => window.renderLayer(o), { ...job, w: 512 })));
    const sub = path.join(repo, 'docs', 'submission');
    fs.mkdirSync(sub, { recursive: true });
    fs.copyFileSync(src, path.join(sub, 'app_icon_512.png'));
    for (const [d, px] of Object.entries(MIPMAP)) {
      const res = path.join(repo, 'app', 'src', 'main', 'res', `mipmap-${d}`);
      fs.mkdirSync(res, { recursive: true });
      execFileSync('ffmpeg', ['-v', 'error', '-y', '-i', src, '-vf', `scale=${px}:${px}:flags=lanczos`, path.join(res, 'ic_launcher.png')]);
    }
    console.log('app_icon 512px + mipmap');
    continue;
  }
  if (job.kind === 'icon') {
    const data = await page.evaluate((o) => window.renderLayer(o), { ...job, w: ICON_SIZE });
    webp(png(data), path.join(dir('icons'), `${job.file}.webp`), true, 90);
    continue;
  }
  if (job.kind === 'motion') {
    for (const [layout, [w, h]] of Object.entries(MOTION)) {
      const file = path.join(dir('motion'), `${job.name}${layout === 'portrait' ? '_portrait' : ''}.mp4`);
      await page.evaluate((o) => window.Anim.setup(o), { region: job.region, grade: job.grade, w, h, layout });
      const { loop, start, stillSec } = await page.evaluate(() => ({ loop: window.Anim.LOOP_SEC, start: window.Anim.start, stillSec: window.Anim.stillSec }));
      // 앱이 처음 재생할 때 건너뛸 자리(정지 그림과 같은 순간)
      fs.writeFileSync(path.join(dir('motion'), 'motion.json'), `${JSON.stringify({ loopMs: loop * 1000, stillMs: Math.round(stillSec * 1000) })}\n`);
      const ff = spawn('ffmpeg', ['-v', 'error', '-y', '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'png', '-i', '-',
        '-c:v', 'libx264', '-preset', 'slow', '-crf', '23', '-pix_fmt', 'yuv420p', '-profile:v', 'high', '-movflags', '+faststart', '-an', file]);
      const t0 = Date.now();
      for (let i = 0; i < loop * FPS; i++) {
        const data = await page.evaluate((t) => window.Anim.frame(t), start + i / FPS);
        if (!ff.stdin.write(png(data))) await new Promise((r) => ff.stdin.once('drain', r));
      }
      ff.stdin.end();
      await new Promise((r, j) => ff.on('close', (c) => (c === 0 ? r() : j(new Error(`ffmpeg ${c}`)))));
      console.log(`${path.relative(out, file)} ${w}x${h} ${loop * FPS}프레임 ${(fs.statSync(file).size / 1024).toFixed(0)}KB ${((Date.now() - t0) / 1000).toFixed(0)}초`);
    }
    continue;
  }
  for (const [layout, [w, h]] of Object.entries(LAYOUTS)) {
    const data = await page.evaluate((o) => window.renderLayer(o), { ...job, w, h, layout });
    const name = layout === 'landscape' ? job.file : `${job.file}_portrait`;
    // 탈것 레이어는 투명도를 남긴다
    webp(png(data), path.join(dir('scenes'), `${name}.webp`), job.kind === 'vehicle' || job.kind === 'rear', 85);
  }
}
await browser.close();
server.close();
fs.rmSync(tmp, { recursive: true, force: true });
