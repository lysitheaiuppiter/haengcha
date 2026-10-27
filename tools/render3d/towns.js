/*
 * 도감 지역 12곳의 정차 화면 장면. stage.js의 무대(단상·조명·카메라)는 그대로 두고,
 * 단상 왼쪽 뒤(횡성 한우 자리)에 지역 상징을 three.js 기본 도형으로 빚어 세우고 하늘·먼 풍경은 2D로 그린다.
 * 지역마다 다음을 둔다.
 *  - key: 주 조명 색
 *  - paint(g, w, h, wide, geo, animated): 배경 그림. animated면 움직일 것(별·불빛 등)은 빼고 fx가 그린다
 *  - build(scene, layout, animated): 3D 상징. 움직이는 배경이면 t초 갱신 함수를 돌려준다
 *  - fx(w, h, wide, geo): 움직이는 배경의 2D 효과 (g, t) => {}
 * 모든 움직임의 주기는 루프 길이(LOOP_SEC)에 맞춘다(fq, 또는 t / LOOP_SEC의 정수 배).
 * 내비게이션처럼 보이지 않게 길·소실점·지도 요소는 넣지 않는다.
 */
(function () {
  const S = window.Stage;
  const T = S.T;
  const { mulberry32, fq, radialTexture, sprite, bokeh, starList, drawStars, floorPool, ridge, drawHall, LOOP_SEC } = S;
  const TAU = Math.PI * 2;
  /** 루프 안에서 n번 도는 0~1 위상. */
  const cyc = (t, n, p = 0) => (((t / LOOP_SEC) * n + p) % 1 + 1) % 1;

  // ---------------- 3D 도우미 ----------------

  // 색은 sRGB 값으로 적고 선형으로 바꿔 넣는다(렌더러 출력이 sRGB라 그대로 넣으면 바래 보인다)
  const lin = (c) => new T.Color(c).convertSRGBToLinear();
  function mat(color, o = {}) {
    const p = { color: lin(color), roughness: o.rough ?? 0.6, metalness: o.metal ?? 0, envMapIntensity: o.env ?? 0.5 };
    if (o.emissive) { p.emissive = lin(o.emissive); p.emissiveIntensity = o.ei ?? 1; }
    if (o.flat) p.flatShading = true;
    if (o.double) p.side = T.DoubleSide;
    if (o.map) p.map = o.map;
    if (o.bump) { p.bumpMap = o.bump; p.bumpScale = o.bumpScale ?? 1; }
    return new T.MeshStandardMaterial(p);
  }
  function mesh(geo, m, pos, rot, scl) {
    const o = new T.Mesh(geo, m);
    if (pos) o.position.set(...pos);
    if (rot) o.rotation.set(...rot);
    if (scl != null) (typeof scl === 'number' ? o.scale.setScalar(scl) : o.scale.set(...scl));
    o.castShadow = true; o.receiveShadow = true;
    return o;
  }
  const box = (w, h, d, m, pos, rot) => mesh(new T.BoxGeometry(w, h, d), m, pos, rot);
  const ball = (r, m, pos, scl, seg = 32) => mesh(new T.SphereGeometry(r, seg, Math.max(12, seg / 2)), m, pos, null, scl);
  const cyl = (r0, r1, h, m, pos, rot, seg = 32) => mesh(new T.CylinderGeometry(r0, r1, h, seg), m, pos, rot);
  const cone = (r, h, m, pos, rot, seg = 16) => mesh(new T.ConeGeometry(r, h, seg), m, pos, rot);
  const ring = (r, tube, m, pos, rot, arc = TAU) => mesh(new T.TorusGeometry(r, tube, 10, 48, arc), m, pos, rot);
  const lathe = (pts, m, pos, seg = 64) => mesh(new T.LatheGeometry(pts.map(([r, y]) => new T.Vector2(r, y)), seg), m, pos);
  /** 두 점 사이 막대. */
  function rod(a, b, r, m, seg = 10) {
    const A = new T.Vector3(...a), B = new T.Vector3(...b);
    const o = mesh(new T.CylinderGeometry(r, r, A.distanceTo(B), seg), m);
    o.position.copy(A).add(B).multiplyScalar(0.5);
    o.quaternion.setFromUnitVectors(new T.Vector3(0, 1, 0), B.clone().sub(A).normalize());
    return o;
  }
  function group(...items) { const g = new T.Group(); items.flat().forEach((i) => g.add(i)); return g; }

  // 상징이 서는 자리: 단상 왼쪽 뒤. landscape는 왼쪽 카드에 가리지 않을 만큼, portrait는 단상 위로 보이게
  const SPOT = { landscape: [-3.7, 0, -6.2], portrait: [-2.1, 0, -7.6] };
  /** 높이를 [fit]에 맞추고 바닥을 땅에 붙여 세운다. 돌려준 묶음에 움직이는 소품을 더 붙일 수 있다. */
  function place(scene, obj, layout, { fit = 2.4, yaw = 0.45, at } = {}) {
    obj.updateMatrixWorld(true);
    let b = new T.Box3().setFromObject(obj);
    obj.scale.multiplyScalar(fit / b.getSize(new T.Vector3()).y);
    obj.updateMatrixWorld(true);
    b = new T.Box3().setFromObject(obj);
    const c = b.getCenter(new T.Vector3());
    obj.position.x -= c.x; obj.position.z -= c.z; obj.position.y -= b.min.y;
    const g = new T.Group(); g.add(obj); g.rotation.y = yaw; g.position.set(...(at || SPOT[layout]));
    scene.add(g);
    return g;
  }
  /** 상징 위 한 점의 화면 좌표(김·불빛 효과 자리). */
  const spotScreen = (geo, wide, y, at) => { const p = at || SPOT[wide ? 'landscape' : 'portrait']; return geo.toScreen(p[0], y, p[2]); };

  /** 둥근 소반(지름 1.8, 높이 0.6). 음식 상징을 올린다. */
  function soban() {
    const wood = mat('#7A4A26', { rough: 0.45, env: 0.6 });
    const legs = [0, 1, 2, 3].map((i) => {
      const a = i * TAU / 4 + TAU / 8;
      return box(0.09, 0.52, 0.09, wood, [Math.cos(a) * 0.6, 0.27, Math.sin(a) * 0.6], [0, -a, 0]);
    });
    return group(
      cyl(0.9, 0.86, 0.07, wood, [0, 0.565, 0], null, 64),
      ring(0.88, 0.03, wood, [0, 0.6, 0], [Math.PI / 2, 0, 0]),
      ring(0.6, 0.025, wood, [0, 0.12, 0], [Math.PI / 2, 0, 0]),
      legs,
    );
  }
  const TABLE = 0.6; // 소반 윗면 높이

  /** 거친 돌결(현무암·화강암) 텍스처. */
  function stoneTexture(seed, base, spot, holes) {
    const c = document.createElement('canvas'); c.width = c.height = 256;
    const g = c.getContext('2d'); const rnd = mulberry32(seed);
    g.fillStyle = base; g.fillRect(0, 0, 256, 256);
    for (let i = 0; i < 1400; i++) {
      g.fillStyle = `rgba(${spot},${0.05 + rnd() * 0.15})`;
      g.beginPath(); g.arc(rnd() * 256, rnd() * 256, 0.5 + rnd() * 2.5, 0, TAU); g.fill();
    }
    for (let i = 0; i < holes; i++) {
      g.fillStyle = `rgba(0,0,0,${0.35 + rnd() * 0.4})`;
      g.beginPath(); g.arc(rnd() * 256, rnd() * 256, 1 + rnd() * 3.5, 0, TAU); g.fill();
    }
    const t = new T.CanvasTexture(c); t.wrapS = t.wrapT = T.RepeatWrapping;
    return t;
  }

  // ---------------- 2D 도우미 ----------------

  function vgrad(g, x, y0, w, y1, stops) {
    const s = g.createLinearGradient(0, y0, 0, y1);
    stops.forEach(([t, c]) => s.addColorStop(t, c));
    g.fillStyle = s; g.fillRect(x, y0, w, y1 - y0 + 1);
  }
  const sky = (g, w, hy, stops) => vgrad(g, 0, 0, w, hy, stops);
  const ground = (g, w, h, y0, stops) => vgrad(g, 0, y0, w, h, stops);
  function moon(g, x, y, r) {
    bokeh(g, x, y, r * 7, 0.22, '190,215,255');
    g.fillStyle = '#F4F1E4'; g.beginPath(); g.arc(x, y, r, 0, TAU); g.fill();
    g.fillStyle = 'rgba(200,196,180,0.35)';
    [[-0.3, -0.2, 0.22], [0.25, 0.15, 0.16], [0.05, 0.4, 0.12]].forEach(([dx, dy, rr]) => { g.beginPath(); g.arc(x + dx * r, y + dy * r, rr * r, 0, TAU); g.fill(); });
  }
  /** 색상환 각도를 'r,g,b'로. */
  function hue(hh, s = 0.75, l = 0.62) {
    const k = (n) => (n + hh / 30) % 12, a = s * Math.min(l, 1 - l);
    const f = (n) => l - a * Math.max(-1, Math.min(k(n) - 3, 9 - k(n), 1));
    return `${Math.round(f(0) * 255)},${Math.round(f(8) * 255)},${Math.round(f(4) * 255)}`;
  }
  /** 불 켜진 건물 줄. 창 불빛 목록을 돌려준다(반짝임 효과용). */
  function skyline(g, x0, x1, baseY, h, seed, { minH = 0.025, maxH = 0.09, color = '#0B1530', lit = '255,214,150', density = 0.3 } = {}) {
    const rnd = mulberry32(seed); const wins = [];
    for (let x = x0; x < x1;) {
      const bw = h * (0.018 + rnd() * 0.03), bh = h * (minH + rnd() * (maxH - minH));
      g.fillStyle = color; g.fillRect(x, baseY - bh, bw, bh + 1);
      const s = Math.max(1, h * 0.0026);
      for (let yy = baseY - bh + h * 0.006; yy < baseY - h * 0.004; yy += h * 0.0075) {
        for (let xx = x + h * 0.004; xx < x + bw - h * 0.004; xx += h * 0.0055) {
          if (rnd() < density) { const a = 0.35 + rnd() * 0.5; g.fillStyle = `rgba(${lit},${a})`; g.fillRect(xx, yy, s, s * 1.3); wins.push({ x: xx, y: yy, p: rnd() * TAU, f: rnd() }); }
        }
      }
      x += bw + h * (0.002 + rnd() * 0.006);
    }
    return wins;
  }
  /** 창 몇 개가 켜졌다 꺼졌다 한다. */
  function blinkWindows(g, wins, h, lit, t) {
    const s = Math.max(1, h * 0.0026);
    wins.forEach((v) => {
      if (v.f < 0.93) return;
      const a = Math.max(0, Math.sin(t * fq(0.4 + v.f) + v.p));
      g.fillStyle = `rgba(${lit},${0.8 * a})`; g.fillRect(v.x, v.y, s, s * 1.3);
    });
  }
  /** 바다 띠와 물가. 물비늘 목록을 돌려준다. */
  function seaBand(g, w, hy, shoreY, h, seed, top, bottom, glintRgb, cx) {
    vgrad(g, 0, hy, w, shoreY, [[0, top], [1, bottom]]);
    const rnd = mulberry32(seed); const list = [];
    for (let i = 0; i < 220; i++) {
      const t = rnd(); const y = hy + 2 + (shoreY - hy - 4) * t * t;
      const x = cx == null ? rnd() * w : cx + (rnd() - 0.5) * 2 * w * (0.02 + 0.1 * t);
      const len = h * (0.006 + 0.02 * t) * (0.5 + rnd());
      list.push({ x, y, len, a: (0.12 + 0.4 * rnd()) * (1 - 0.5 * t), p: rnd() * TAU, f: 1.5 + rnd() * 3 });
    }
    return list;
  }
  function drawGlints(g, list, h, rgb, t) {
    list.forEach((s) => {
      const a = t == null ? s.a : Math.max(0, Math.sin(t * fq(s.f) + s.p)) * s.a * 1.6;
      if (a < 0.02) return;
      g.fillStyle = `rgba(${rgb},${a})`; g.fillRect(s.x - s.len / 2, s.y, s.len, Math.max(1, h * 0.0017));
    });
  }
  /** 김: 루프에 두 번 피어오른다. */
  function steam(g, x, y, h, t, n = 3, seed = 3) {
    const rnd = mulberry32(seed);
    for (let i = 0; i < n; i++) {
      const p = rnd(), sway = rnd() * TAU;
      for (let k = 0; k < 5; k++) {
        const u = cyc(t, 2, p + k / 5);
        const yy = y - u * h * 0.13;
        const xx = x + (i - (n - 1) / 2) * h * 0.022 + Math.sin(u * 5 + sway) * h * 0.012;
        bokeh(g, xx, yy, h * (0.012 + 0.022 * u), 0.2 * Math.sin(Math.PI * u), '255,250,240');
      }
    }
  }
  /** 매달린 초롱(청사초롱·홍등): 줄을 그리고 초롱 자리를 돌려준다. */
  function lanternString(g, x0, y0, x1, y1, sag, n, h, color) {
    const pts = [];
    g.strokeStyle = 'rgba(20,14,10,0.7)'; g.lineWidth = Math.max(1, h * 0.0015);
    g.beginPath();
    for (let i = 0; i <= 40; i++) {
      const u = i / 40, x = x0 + (x1 - x0) * u, y = y0 + (y1 - y0) * u + sag * 4 * u * (1 - u);
      if (i === 0) g.moveTo(x, y); else g.lineTo(x, y);
    }
    g.stroke();
    for (let i = 1; i < n; i++) {
      const u = i / n; pts.push({ x: x0 + (x1 - x0) * u, y: y0 + (y1 - y0) * u + sag * 4 * u * (1 - u) + h * 0.012, color, p: i * 1.7 });
    }
    return pts;
  }
  function drawLanterns(g, pts, h, t) {
    pts.forEach((l) => {
      const fl = t == null ? 1 : 0.8 + 0.2 * Math.sin(t * fq(3) + l.p) * Math.sin(t * fq(1.3) + l.p * 2);
      const dx = t == null ? 0 : Math.sin(t * fq(0.6) + l.p) * h * 0.002;
      bokeh(g, l.x + dx, l.y, h * 0.03, 0.35 * fl, l.color === 'blue' ? '120,160,255' : '255,110,80');
      const r = h * 0.008;
      g.fillStyle = l.color === 'blue' ? '#3C5BC8' : '#D8382A';
      g.beginPath(); g.ellipse(l.x + dx, l.y, r * 0.8, r * 1.1, 0, 0, TAU); g.fill();
      if (l.color === 'blue') { g.fillStyle = '#D8382A'; g.beginPath(); g.ellipse(l.x + dx, l.y + r * 0.5, r * 0.8, r * 0.6, 0, 0, Math.PI); g.fill(); }
      g.fillStyle = `rgba(255,220,150,${0.6 * fl})`; g.beginPath(); g.ellipse(l.x + dx, l.y, r * 0.35, r * 0.7, 0, 0, TAU); g.fill();
    });
  }
  /** 기와지붕 마을 실루엣. */
  function hanokRoofs(g, w, baseY, h, seed, color, count) {
    const rnd = mulberry32(seed);
    g.fillStyle = color;
    for (let i = 0; i < count; i++) {
      const x = (i + rnd() * 0.6) / count * w, W = h * (0.09 + rnd() * 0.06), H = h * (0.045 + rnd() * 0.025);
      drawHall(g, x, baseY + h * 0.004 * rnd(), W, H);
    }
    g.fillRect(0, baseY - h * 0.004, w, h * 0.01);
  }
  /** 소나무 실루엣. */
  function pine(g, x, baseY, H, color, seed) {
    const rnd = mulberry32(seed);
    g.fillStyle = color;
    g.beginPath(); g.moveTo(x - H * 0.025, baseY); g.quadraticCurveTo(x + H * 0.05, baseY - H * 0.5, x - H * 0.02, baseY - H * 0.9); g.lineTo(x + H * 0.01, baseY - H * 0.9);
    g.quadraticCurveTo(x + H * 0.08, baseY - H * 0.5, x + H * 0.025, baseY); g.closePath(); g.fill();
    for (let i = 0; i < 4; i++) {
      const y = baseY - H * (0.55 + i * 0.13), cx = x + (rnd() - 0.4) * H * 0.2;
      g.beginPath(); g.ellipse(cx, y, H * (0.2 - i * 0.03), H * 0.05, (rnd() - 0.5) * 0.3, 0, TAU); g.fill();
    }
  }
  /** 땅 위 풀결·돌결 같은 짧은 획. */
  function strokes(g, w, h, hy, seed, n, rgbs, len0, len1) {
    const rnd = mulberry32(seed);
    for (let i = 0; i < n; i++) {
      const t = rnd(); const y = hy + 4 + (h - hy) * t * t; const len = h * (len0 + len1 * t);
      g.strokeStyle = `rgba(${rgbs[i % rgbs.length]},${0.1 + 0.2 * rnd()})`; g.lineWidth = 1 + t * 1.5;
      const x = rnd() * w; g.beginPath(); g.moveTo(x, y); g.lineTo(x + (rnd() - 0.5) * len * 0.4, y - len); g.stroke();
    }
  }
  /** 박석·벽돌 같은 바닥 무늬. */
  function paving(g, w, h, hy, seed, rgb) {
    const rnd = mulberry32(seed);
    for (let i = 0; i < 520; i++) {
      const t = rnd(); const y = hy + 6 + (h - hy) * t * t; const r = h * (0.006 + 0.03 * t);
      g.strokeStyle = `rgba(${rgb},${0.05 + 0.07 * rnd()})`; g.lineWidth = 1 + t;
      g.beginPath(); g.ellipse(rnd() * w, y, r, r * (0.25 + 0.2 * t), 0, 0, TAU); g.stroke();
    }
  }
  /** 반딧불·불티처럼 떠도는 점들(움직이는 배경). */
  function floaters(w, y0, y1, n, seed) {
    const rnd = mulberry32(seed);
    return Array.from({ length: n }, () => ({ x: rnd() * w, y: y0 + rnd() * (y1 - y0), p: rnd() * TAU, f: 0.3 + rnd() * 0.5 }));
  }
  function drawFloaters(g, list, h, rgb, t, r = 0.006) {
    list.forEach((f) => {
      const a = Math.max(0, Math.sin(t * fq(1.1) + f.p * 3)) * 0.9;
      bokeh(g, f.x + Math.sin(t * fq(f.f) + f.p) * h * 0.03, f.y + Math.cos(t * fq(f.f * 0.8) + f.p) * h * 0.015, h * r, a, rgb);
    });
  }
  /** 노을 하늘을 오가는 구름. */
  function clouds(w, h, hy, n, seed, rgb) {
    const rnd = mulberry32(seed);
    const list = Array.from({ length: n }, () => ({ x: rnd() * w, y: hy * (0.15 + rnd() * 0.5), rx: w * (0.07 + rnd() * 0.08), ry: h * (0.012 + rnd() * 0.012), v: w * 0.04, p: rnd() * TAU, a: 0.08 + rnd() * 0.1 }));
    return (g, t) => list.forEach((c) => {
      const x = c.x + Math.sin(t * fq(0.3) + c.p) * c.v;
      g.save(); g.translate(x, c.y); g.scale(1, c.ry / c.rx);
      const b = g.createRadialGradient(0, 0, 0, 0, 0, c.rx);
      b.addColorStop(0, `rgba(${rgb},${c.a})`); b.addColorStop(1, `rgba(${rgb},0)`);
      g.fillStyle = b; g.beginPath(); g.arc(0, 0, c.rx, 0, TAU); g.fill(); g.restore();
    });
  }
  /** 사장교(광안대교·인천대교·돌산대교): 상판, 주탑, 케이블. 케이블 불빛 자리를 돌려준다. */
  function bridge(g, x0, x1, deckY, h, towers, towerH, color, { suspension = false } = {}) {
    g.strokeStyle = color; g.fillStyle = color;
    g.fillRect(x0, deckY, x1 - x0, Math.max(2, h * 0.004));
    const lights = [];
    towers.forEach((tx) => {
      g.fillRect(tx - h * 0.0035, deckY - towerH, h * 0.007, towerH + h * 0.012);
    });
    g.lineWidth = Math.max(1, h * 0.0012);
    if (suspension) {
      // 주탑 사이에 처진 주케이블, 양끝으로 내려가는 케이블
      const pts = [[x0, deckY], ...towers.map((x) => [x, deckY - towerH]), [x1, deckY]];
      for (let i = 0; i < pts.length - 1; i++) {
        const [ax, ay] = pts[i], [bx, by] = pts[i + 1];
        const sag = i === 0 || i === pts.length - 2 ? h * 0.004 : towerH * 0.85;
        g.beginPath();
        for (let k = 0; k <= 30; k++) {
          const u = k / 30, x = ax + (bx - ax) * u, y = ay + (by - ay) * u + sag * 4 * u * (1 - u) * (i === 0 || i === pts.length - 2 ? 1 : 1);
          if (k === 0) g.moveTo(x, y); else g.lineTo(x, y);
          if (k % 2 === 0) lights.push({ x, y, u: (i + u) / (pts.length - 1) });
        }
        g.stroke();
      }
    } else {
      towers.forEach((tx) => {
        for (let k = 1; k <= 7; k++) {
          [-1, 1].forEach((sd) => {
            const ex = tx + sd * k * h * 0.022;
            g.beginPath(); g.moveTo(tx, deckY - towerH * 0.95); g.lineTo(ex, deckY); g.stroke();
            for (let q = 1; q < 4; q++) { const u = q / 4; lights.push({ x: tx + (ex - tx) * u, y: deckY - towerH * 0.95 * (1 - u), u: (ex - x0) / (x1 - x0) }); }
          });
        }
      });
    }
    for (let x = x0; x < x1; x += h * 0.012) lights.push({ x, y: deckY, u: (x - x0) / (x1 - x0), deck: true });
    return lights;
  }
  function drawBridgeLights(g, lights, h, t, baseHue) {
    lights.forEach((l) => {
      const hh = t == null ? baseHue + l.u * 80 : (baseHue + l.u * 120 + 360 * cyc(t, 1)) % 360;
      const rgb = l.deck ? '255,226,170' : hue(hh, 0.85, 0.65);
      bokeh(g, l.x, l.y, h * (l.deck ? 0.004 : 0.006), 0.85, rgb);
    });
  }
  /** 물에 비친 불빛 기둥. */
  function reflections(n, w, x0, x1, hy, shoreY, seed) {
    const rnd = mulberry32(seed);
    return Array.from({ length: n }, () => ({ x: x0 + rnd() * (x1 - x0), y: hy + 2, len: (shoreY - hy) * (0.2 + rnd() * 0.5), rgb: [hue(rnd() * 360, 0.8, 0.65), '255,214,150', '255,240,220'][Math.floor(rnd() * 3)], p: rnd() * TAU }));
  }
  function drawReflections(g, list, h, t) {
    list.forEach((r) => {
      for (let k = 0; k < 8; k++) {
        const u = k / 8, y = r.y + r.len * u;
        const a = (1 - u) * 0.35 * (t == null ? 0.8 : 0.5 + 0.5 * Math.sin(t * fq(2.2) + r.p + k));
        g.fillStyle = `rgba(${r.rgb},${a})`; g.fillRect(r.x - h * (0.004 + 0.004 * u), y, h * (0.008 + 0.008 * u), Math.max(1, h * 0.003));
      }
    });
  }

  // ---------------- 서울: 해치 석상, 궁궐 담장, 남산 서울타워 ----------------

  const seoulTower = (w, hy, h, wide) => ({ x: w * (wide ? 0.8 : 0.78), y: hy - h * 0.06 });
  const seoul = {
    key: 0xffe2c4,
    paint(g, w, h, wide, geo, animated) {
      const { horizonY: hy, pool } = geo;
      sky(g, w, hy, [[0, '#070F26'], [0.6, '#15244A'], [1, '#33406E']]);
      if (!animated) drawStars(g, starList(w, hy * 0.7, wide ? 110 : 60, 31));
      skyline(g, 0, w, hy - h * 0.01, h, 5, { color: '#0D1A38', maxH: 0.07, lit: '255,222,170' });
      // 남산과 서울타워
      const tw = seoulTower(w, hy, h, wide);
      g.fillStyle = '#0A1430'; g.beginPath(); g.ellipse(tw.x, hy, h * 0.2, h * 0.075, 0, Math.PI, 0); g.fill();
      g.fillRect(tw.x - h * 0.004, tw.y - h * 0.09, h * 0.008, h * 0.09);
      g.beginPath(); g.ellipse(tw.x, tw.y - h * 0.095, h * 0.016, h * 0.01, 0, 0, TAU); g.fill();
      g.fillRect(tw.x - h * 0.0015, tw.y - h * 0.14, h * 0.003, h * 0.045);
      g.fillStyle = 'rgba(255,226,180,0.9)'; g.fillRect(tw.x - h * 0.013, tw.y - h * 0.097, h * 0.026, h * 0.003);
      if (!animated) { bokeh(g, tw.x, tw.y - h * 0.095, h * 0.035, 0.5, '140,200,255'); bokeh(g, tw.x, tw.y - h * 0.14, h * 0.008, 0.9, '255,90,90'); }
      // 궁궐 문루와 담장
      g.fillStyle = '#08112A';
      drawHall(g, w * (wide ? 0.42 : 0.3), hy + h * 0.004, h * 0.22, h * 0.1);
      g.fillRect(0, hy - h * 0.024, w, h * 0.028);
      g.fillRect(0, hy - h * 0.031, w, h * 0.009);
      // 박석 마당
      ground(g, w, h, hy, [[0, '#2D3550'], [0.4, '#1A2032'], [1, '#070A12']]);
      paving(g, w, h, hy, 41, '210,220,255');
      floorPool(g, pool, 'rgba(255,214,160,0.28)', 'rgba(140,160,220,0.10)');
    },
    async build(scene, layout) {
      const stone = mat('#BDAA88', { rough: 0.85, env: 0.4, bump: stoneTexture(3, '#888', '40,40,40', 60), bumpScale: 0.08 });
      const base = mat('#7D7466', { rough: 0.85, env: 0.3 });
      const dark = mat('#17120E', { rough: 0.35 });
      const white = mat('#F2EEE4', { rough: 0.4 });
      const parts = [
        box(1.5, 0.45, 1.05, base, [0, 0.225, 0]),
        box(1.62, 0.08, 1.16, base, [0, 0.49, 0]),
        ball(0.5, stone, [0, 1.0, -0.15], [0.95, 1.05, 1.15]),
        ball(0.32, stone, [-0.34, 0.8, -0.32]), ball(0.32, stone, [0.34, 0.8, -0.32]),
        cyl(0.11, 0.13, 0.62, stone, [-0.22, 0.83, 0.28]), cyl(0.11, 0.13, 0.62, stone, [0.22, 0.83, 0.28]),
        ball(0.14, stone, [-0.22, 0.58, 0.38], [1, 0.7, 1.3]), ball(0.14, stone, [0.22, 0.58, 0.38], [1, 0.7, 1.3]),
        ball(0.46, stone, [0, 1.74, 0.2]),
        ball(0.27, stone, [0, 1.6, 0.56], [1.25, 0.85, 1]),
        ball(0.075, dark, [0, 1.69, 0.81], [1.3, 0.9, 1]),
        ball(0.115, white, [-0.18, 1.88, 0.55]), ball(0.115, white, [0.18, 1.88, 0.55]),
        ball(0.06, dark, [-0.18, 1.88, 0.65]), ball(0.06, dark, [0.18, 1.88, 0.65]),
        // 웃는 입
        ring(0.13, 0.022, dark, [0, 1.52, 0.72], [0, 0, Math.PI], Math.PI),
        // 정수리의 뿔
        cone(0.08, 0.34, stone, [0, 2.27, 0.12], [-0.25, 0, 0]),
        // 말린 꼬리
        ring(0.2, 0.065, stone, [0, 1.08, -0.72], [0, Math.PI / 2, 0], Math.PI * 1.4),
        // 목 방울
        ball(0.09, mat('#D8A84A', { metal: 1, rough: 0.3, env: 1.2 }), [0, 1.32, 0.48]),
      ];
      // 곱슬 갈기
      for (let i = 0; i < 14; i++) {
        const a = (i / 14) * TAU;
        parts.push(ball(0.15, stone, [Math.cos(a) * 0.43, 1.74 + Math.sin(a) * 0.43, -0.02]));
      }
      // 뒷다리 발
      parts.push(ball(0.13, stone, [-0.45, 0.6, -0.1], [1, 0.6, 1.4]), ball(0.13, stone, [0.45, 0.6, -0.1], [1, 0.6, 1.4]));
      place(scene, group(parts), layout, { fit: 2.6, yaw: 0.5 });
      return () => {};
    },
    fx(w, h, wide, geo) {
      const hy = geo.horizonY, tw = seoulTower(w, hy, h, wide);
      const stars = starList(w, hy * 0.7, wide ? 110 : 60, 31);
      const wins = []; // 창 반짝임은 정지 그림의 창 자리를 다시 만든다
      const c = document.createElement('canvas'); c.width = w; c.height = h;
      wins.push(...skyline(c.getContext('2d'), 0, w, hy - h * 0.01, h, 5, { color: '#0D1A38', maxH: 0.07, lit: '255,222,170' }));
      return (g, t) => {
        drawStars(g, stars, t);
        blinkWindows(g, wins, h, '255,222,170', t);
        // 서울타워 조명이 천천히 색을 바꾸고 꼭대기 등이 깜빡인다
        bokeh(g, tw.x, tw.y - h * 0.095, h * 0.04, 0.55, hue((200 + 360 * cyc(t, 1)) % 360));
        bokeh(g, tw.x, tw.y - h * 0.14, h * 0.009, 0.3 + 0.7 * Math.max(0, Math.sin(t * fq(2.5))), '255,90,90');
      };
    },
  };

  // ---------------- 부산: 광안대교, 해운대 고층 불빛, 빨간 등대와 갈매기 ----------------

  function busanLayout(w, h, hy, wide) {
    return { bx0: w * (wide ? 0.0 : -0.1), bx1: w * (wide ? 0.66 : 0.72), towers: wide ? [w * 0.24, w * 0.44] : [w * 0.2, w * 0.46], sx0: w * (wide ? 0.6 : 0.62) };
  }
  const busan = {
    key: 0xdfe8ff,
    paint(g, w, h, wide, geo, animated) {
      const { horizonY: hy, pool, toScreen } = geo;
      const shoreY = toScreen(0, 0, -9.5)[1];
      const L = busanLayout(w, h, hy, wide);
      sky(g, w, hy, [[0, '#050B22'], [0.6, '#141F4A'], [1, '#2C2F62']]);
      if (!animated) drawStars(g, starList(w, hy * 0.6, wide ? 90 : 50, 8));
      moon(g, w * (wide ? 0.12 : 0.15), hy * 0.3, h * 0.026);
      // 해운대 고층 건물
      skyline(g, L.sx0, w, hy, h, 12, { color: '#0A1232', minH: 0.05, maxH: 0.15, lit: '210,225,255', density: 0.35 });
      const glints = seaBand(g, w, hy, shoreY, h, 4, '#0B1A3C', '#163460');
      if (!animated) drawGlints(g, glints, h, '200,215,255', null);
      const lights = bridge(g, L.bx0, L.bx1, hy - h * 0.018, h, L.towers, h * 0.06, '#0B1430', { suspension: true });
      if (!animated) drawBridgeLights(g, lights, h, null, 210);
      const refl = reflections(wide ? 26 : 16, w, L.sx0, w, hy, shoreY, 6);
      if (!animated) drawReflections(g, refl, h, null);
      // 모래사장과 물가 거품
      ground(g, w, h, shoreY, [[0, '#3B3446'], [1, '#100D16']]);
      g.strokeStyle = 'rgba(220,235,255,0.35)'; g.lineWidth = h * 0.004; g.beginPath();
      for (let x = 0; x <= w; x += 6) { const y = shoreY + Math.sin(x / w * 23) * h * 0.003; if (x === 0) g.moveTo(x, y); else g.lineTo(x, y); }
      g.stroke();
      floorPool(g, pool, 'rgba(255,214,160,0.26)', 'rgba(110,170,220,0.10)');
    },
    async build(scene, layout, animated) {
      const red = mat('#C9282F', { rough: 0.35, env: 0.7 });
      const white = mat('#F1EFEA', { rough: 0.4 });
      const dark = mat('#2A1416', { rough: 0.4, metal: 0.4 });
      const rock = mat('#4E5560', { rough: 0.95, flat: true });
      const glass = mat('#FFE6A8', { emissive: '#FFD27A', ei: 1.6, rough: 0.2 });
      const rocks = [];
      const rnd = mulberry32(7);
      for (let i = 0; i < 9; i++) {
        const a = (i / 9) * TAU;
        const r = mesh(new T.DodecahedronGeometry(0.35 + rnd() * 0.2), rock, [Math.cos(a) * 0.75, 0.18, Math.sin(a) * 0.75], [rnd() * 3, rnd() * 3, 0]);
        rocks.push(r);
      }
      const tower = group(
        rocks,
        cyl(0.7, 0.8, 0.35, rock, [0, 0.17, 0], null, 9),
        lathe([[0, 0], [0.5, 0], [0.42, 2.2], [0, 2.2]], red, [0, 0.35, 0]),
        cyl(0.47, 0.49, 0.12, white, [0, 0.95, 0]), cyl(0.44, 0.46, 0.12, white, [0, 1.65, 0]),
        box(0.22, 0.38, 0.05, dark, [0, 0.58, 0.47]),
        cyl(0.6, 0.6, 0.06, dark, [0, 2.58, 0]),
        cyl(0.3, 0.3, 0.42, glass, [0, 2.82, 0]),
        ball(0.33, red, [0, 3.04, 0], [1, 0.55, 1]),
        cone(0.04, 0.3, dark, [0, 3.3, 0]),
      );
      const g = place(scene, tower, layout, { fit: 3.0, yaw: 0.3 });
      const lampY = 2.82 / 3.45 * 3.0;
      const lamp = new T.PointLight(0xffd27a, 2.0, 7, 2); lamp.position.set(0, lampY, 0); g.add(lamp);
      const glowSp = sprite(radialTexture([[0, 'rgba(255,230,160,1)'], [0.3, 'rgba(255,214,130,0.4)'], [1, 'rgba(255,214,130,0)']]), 0.9);
      glowSp.position.set(0, lampY, 0); glowSp.scale.set(1.6, 1.6, 1); g.add(glowSp);
      // 갈매기 두 마리
      const gulls = [0, 1].map((i) => {
        const wm = mat('#F4F4F2', { rough: 0.6 });
        const tip = mat('#3A3E44', { rough: 0.6 });
        const body = group(
          ball(0.12, wm, [0, 0, 0], [1, 0.9, 2.2]),
          ball(0.08, wm, [0, 0.05, 0.25]),
          cone(0.025, 0.1, mat('#F0A030'), [0, 0.04, 0.36], [Math.PI / 2, 0, 0]),
        );
        const wings = [-1, 1].map((sd) => {
          const pivot = new T.Group();
          pivot.add(box(0.5, 0.02, 0.16, wm, [sd * 0.26, 0, 0]));
          pivot.add(box(0.14, 0.021, 0.12, tip, [sd * 0.46, 0, -0.02]));
          pivot.position.set(sd * 0.06, 0.03, 0.02); body.add(pivot);
          return { pivot, sd };
        });
        const holder = new T.Group(); holder.add(body); g.add(holder);
        return { holder, body, wings, p: i * Math.PI + 0.6, r: 1.5 + i * 0.4, y: 3.1 + i * 0.35 };
      });
      const pose = (t) => {
        gulls.forEach((k) => {
          const a = t * fq(0.57) * (k.r > 1.6 ? -1 : 1) + k.p;
          k.holder.position.set(Math.cos(a) * k.r, k.y + 0.12 * Math.sin(t * fq(1.1) + k.p), Math.sin(a) * k.r);
          k.holder.rotation.y = -a + (k.r > 1.6 ? 0 : Math.PI);
          k.body.rotation.z = (k.r > 1.6 ? 1 : -1) * 0.35;
          const flap = Math.sin(t * fq(4.4) + k.p) * 0.55;
          k.wings.forEach((wg) => { wg.pivot.rotation.z = wg.sd * flap; });
        });
      };
      pose(2.0);
      if (!animated) return () => {};
      return (t) => {
        pose(t);
        const beat = 0.6 + 0.4 * Math.max(0, Math.sin(t * fq(1.4)));
        lamp.intensity = 2.0 * beat; glowSp.material.opacity = 0.9 * beat;
      };
    },
    fx(w, h, wide, geo) {
      const { horizonY: hy, toScreen } = geo;
      const shoreY = toScreen(0, 0, -9.5)[1];
      const L = busanLayout(w, h, hy, wide);
      const c = document.createElement('canvas'); c.width = w; c.height = h;
      const lights = bridge(c.getContext('2d'), L.bx0, L.bx1, hy - h * 0.018, h, L.towers, h * 0.06, '#000', { suspension: true });
      const stars = starList(w, hy * 0.6, wide ? 90 : 50, 8);
      const glints = seaBand(c.getContext('2d'), w, hy, shoreY, h, 4, '#000', '#000');
      const refl = reflections(wide ? 26 : 16, w, L.sx0, w, hy, shoreY, 6);
      return (g, t) => {
        drawStars(g, stars, t);
        drawGlints(g, glints, h, '200,215,255', t);
        drawReflections(g, refl, h, t);
        drawBridgeLights(g, lights, h, t, 210);
      };
    },
  };

  // ---------------- 대구: 해 질 녘 사과밭, 팔공산, 83타워 ----------------

  const daegu = {
    key: 0xffc8a0,
    paint(g, w, h, wide, geo) {
      const { horizonY: hy, pool } = geo;
      sky(g, w, hy, [[0, '#1B1E45'], [0.5, '#4A3D6E'], [0.82, '#C0607A'], [1, '#F2A35E']]);
      bokeh(g, w * (wide ? 0.55 : 0.5), hy, h * 0.3, 0.35, '255,180,110');
      ridge(g, w, hy - h * 0.004, h * 0.14, 27, '#6A4E7E');
      ridge(g, w, hy, h * 0.085, 14, '#46385F');
      // 83타워
      const tx = w * (wide ? 0.86 : 0.82);
      g.fillStyle = '#2E2645'; g.beginPath(); g.ellipse(tx, hy, h * 0.12, h * 0.05, 0, Math.PI, 0); g.fill();
      g.fillRect(tx - h * 0.004, hy - h * 0.14, h * 0.008, h * 0.1);
      g.beginPath(); g.ellipse(tx, hy - h * 0.14, h * 0.012, h * 0.016, 0, 0, TAU); g.fill();
      g.fillRect(tx - h * 0.0015, hy - h * 0.18, h * 0.003, h * 0.03);
      bokeh(g, tx, hy - h * 0.14, h * 0.02, 0.6, '255,200,140');
      // 사과밭 줄: 둥근 나무가 줄지어 선다
      const rnd = mulberry32(19);
      [0.006, 0.02, 0.04].forEach((dy, row) => {
        const n = wide ? 22 - row * 5 : 12 - row * 3;
        for (let i = 0; i < n; i++) {
          const x = (i + 0.5 + (rnd() - 0.5) * 0.3) / n * w, y = hy + h * dy, r = h * (0.012 + row * 0.008);
          g.fillStyle = ['#2F4A2A', '#29422A', '#223A24'][row]; g.beginPath(); g.arc(x, y - r, r, 0, TAU); g.fill();
          for (let k = 0; k < 3; k++) { g.fillStyle = 'rgba(220,50,50,0.8)'; g.beginPath(); g.arc(x + (rnd() - 0.5) * r * 1.4, y - r + (rnd() - 0.5) * r * 1.2, r * 0.13, 0, TAU); g.fill(); }
        }
      });
      ground(g, w, h, hy + h * 0.04, [[0, '#3E5530'], [0.35, '#283C20'], [1, '#0D160B']]);
      strokes(g, w, h, hy + h * 0.04, 44, 700, ['150,170,90', '20,35,15'], 0.004, 0.016);
      floorPool(g, pool, 'rgba(255,200,130,0.28)', 'rgba(200,170,90,0.10)');
    },
    async build(scene, layout, animated) {
      const bark = mat('#5A3A22', { rough: 0.9 });
      const leaf = mat('#3F7A38', { rough: 0.85, env: 0.3 });
      const leaf2 = mat('#4F8E42', { rough: 0.85, env: 0.3 });
      const apple = mat('#C8202B', { rough: 0.28, env: 0.9 });
      const stem = mat('#4A3018', { rough: 0.8 });
      const wood = mat('#A57446', { rough: 0.7 });
      const canopy = [[0, 2.0, 0, 0.78, leaf], [-0.6, 1.8, 0.1, 0.55, leaf2], [0.62, 1.82, 0.05, 0.56, leaf2], [0, 2.45, -0.1, 0.58, leaf], [0.15, 1.85, 0.55, 0.5, leaf2], [-0.3, 2.25, 0.35, 0.45, leaf]];
      const crown = group(canopy.map(([x, y, z, r, m]) => ball(r, m, [x, y, z])));
      const rnd = mulberry32(5);
      for (let i = 0; i < 22; i++) {
        const [cx, cy, cz, r] = canopy[i % canopy.length];
        const th = rnd() * TAU, ph = Math.acos(0.2 + rnd() * 0.75);
        const d = new T.Vector3(Math.cos(th) * Math.sin(ph), Math.cos(ph) - 0.3, Math.sin(th) * Math.sin(ph)).normalize();
        if (d.z < -0.3) d.z = -d.z;
        crown.add(ball(0.1, apple, [cx + d.x * r, cy + d.y * r, cz + d.z * r]));
      }
      const tree = group(
        cyl(0.12, 0.2, 1.4, bark, [0, 0.7, 0], null, 12),
        rod([0, 1.1, 0], [-0.45, 1.6, 0.1], 0.07, bark), rod([0, 1.2, 0], [0.45, 1.65, 0.05], 0.07, bark),
        crown,
      );
      // 사과 상자와 떨어진 사과
      const crate = group(
        box(0.9, 0.06, 0.6, wood, [0, 0.03, 0]),
        box(0.9, 0.1, 0.05, wood, [0, 0.12, 0.28]), box(0.9, 0.1, 0.05, wood, [0, 0.3, 0.28]),
        box(0.9, 0.1, 0.05, wood, [0, 0.12, -0.28]), box(0.9, 0.1, 0.05, wood, [0, 0.3, -0.28]),
        box(0.05, 0.38, 0.6, wood, [0.43, 0.19, 0]), box(0.05, 0.38, 0.6, wood, [-0.43, 0.19, 0]),
      );
      for (let i = 0; i < 14; i++) crate.add(ball(0.11, apple, [-0.3 + (i % 5) * 0.15, 0.36 + Math.floor(i / 5) * 0.08, -0.18 + (Math.floor(i / 5) % 3) * 0.18 + (rnd() - 0.5) * 0.05]));
      crate.position.set(0.95, 0, 0.55); crate.rotation.y = -0.4;
      const fallen = ball(0.11, apple, [-0.5, 0.11, 0.7]);
      const fallenStem = cyl(0.012, 0.012, 0.08, stem, [-0.5, 0.24, 0.7]);
      const all = group(tree, crate, fallen, fallenStem);
      place(scene, all, layout, { fit: 2.9, yaw: 0.35 });
      if (!animated) return () => {};
      return (t) => { crown.rotation.z = 0.015 * Math.sin(t * fq(0.8)); };
    },
    fx(w, h, wide, geo) {
      const hy = geo.horizonY;
      const cl = clouds(w, h, hy, wide ? 5 : 3, 61, '255,205,190');
      // 꽃잎이 바람에 날린다
      const rnd = mulberry32(88);
      const petals = Array.from({ length: wide ? 26 : 16 }, () => ({ x: rnd() * w, y: hy * (0.3 + rnd() * 1.4), p: rnd(), s: 0.6 + rnd() * 0.8 }));
      return (g, t) => {
        cl(g, t);
        petals.forEach((k) => {
          const u = cyc(t, 1, k.p);
          const x = (k.x + u * w * 0.5) % w, y = k.y + Math.sin(u * TAU * 2) * h * 0.02 + u * h * 0.08;
          g.fillStyle = `rgba(255,214,224,${0.55 * Math.sin(Math.PI * u)})`;
          g.beginPath(); g.ellipse(x, y, h * 0.005 * k.s, h * 0.003 * k.s, u * TAU * 2, 0, TAU); g.fill();
        });
      };
    },
  };

  // ---------------- 인천: 차이나타운 패루와 홍등, 인천대교, 짜장면 ----------------

  function paifang(g, cx, baseY, W, H) {
    const pill = '#A3241E', roof = '#1F5E4E', gold = '#E2B04A';
    g.fillStyle = pill;
    [-0.42, -0.16, 0.16, 0.42].forEach((u) => g.fillRect(cx + u * W - W * 0.02, baseY - H * 0.62, W * 0.04, H * 0.62));
    g.fillRect(cx - W * 0.46, baseY - H * 0.62, W * 0.92, H * 0.05);
    g.fillStyle = gold; g.fillRect(cx - W * 0.12, baseY - H * 0.6, W * 0.24, H * 0.08);
    // 세 겹 지붕(가운데가 높다)
    [[0, 0.66, 0.5, 0.3], [-0.3, 0.6, 0.3, 0.2], [0.3, 0.6, 0.3, 0.2]].forEach(([u, v, rw, rh]) => {
      g.fillStyle = roof;
      const x = cx + u * W, y = baseY - H * v;
      g.beginPath(); g.moveTo(x - W * rw * 0.6, y); g.quadraticCurveTo(x - W * rw * 0.4, y - H * rh * 0.2, x - W * rw * 0.3, y - H * rh * 0.6);
      g.lineTo(x + W * rw * 0.3, y - H * rh * 0.6); g.quadraticCurveTo(x + W * rw * 0.4, y - H * rh * 0.2, x + W * rw * 0.6, y); g.closePath(); g.fill();
    });
  }
  const incheonBowlY = 2.0;
  const incheon = {
    key: 0xffd0b0,
    paint(g, w, h, wide, geo, animated) {
      const { horizonY: hy, pool } = geo;
      sky(g, w, hy, [[0, '#0A0E24'], [0.6, '#1F1A3C'], [1, '#43284A']]);
      if (!animated) drawStars(g, starList(w, hy * 0.6, wide ? 80 : 45, 12));
      // 인천대교(먼 바다)와 항만 크레인
      g.fillStyle = '#121633'; g.fillRect(0, hy - h * 0.006, w, h * 0.01);
      const lights = bridge(g, w * (wide ? 0.55 : 0.5), w * 1.05, hy - h * 0.014, h, [w * (wide ? 0.8 : 0.78)], h * 0.075, '#141838');
      if (!animated) drawBridgeLights(g, lights, h, null, 30);
      g.strokeStyle = '#141838'; g.lineWidth = Math.max(2, h * 0.003);
      [0.12, 0.2].forEach((u) => { const x = w * u; g.beginPath(); g.moveTo(x, hy); g.lineTo(x, hy - h * 0.06); g.lineTo(x + h * 0.06, hy - h * 0.06); g.moveTo(x - h * 0.02, hy - h * 0.06); g.lineTo(x, hy - h * 0.06); g.stroke(); });
      // 차이나타운 패루
      paifang(g, w * (wide ? 0.4 : 0.52), hy + h * 0.01, h * 0.3, h * 0.17);
      const strings = [
        ...lanternString(g, 0, hy * 0.62, w * 0.5, hy * 0.7, h * 0.05, wide ? 9 : 6, h, 'red'),
        ...lanternString(g, w * 0.5, hy * 0.7, w, hy * 0.6, h * 0.05, wide ? 9 : 6, h, 'red'),
      ];
      if (!animated) drawLanterns(g, strings, h, null);
      ground(g, w, h, hy + h * 0.01, [[0, '#44261F'], [0.4, '#2A1713'], [1, '#0E0807']]);
      paving(g, w, h, hy, 23, '255,190,160');
      floorPool(g, pool, 'rgba(255,190,140,0.30)', 'rgba(220,120,90,0.10)');
    },
    async build(scene, layout) {
      const porcelain = mat('#F5F2EA', { rough: 0.22, env: 0.9 });
      const rim = mat('#B8302A', { rough: 0.3 });
      const noodle = mat('#E8C474', { rough: 0.55 });
      const sauce = mat('#2B1709', { rough: 0.18, env: 1.0 });
      const bits = [mat('#4A2C14', { rough: 0.3 }), mat('#7BA34A', { rough: 0.4 }), mat('#E9DDB0', { rough: 0.4 })];
      const cucumber = mat('#5FA24A', { rough: 0.4 });
      const steel = mat('#C9CED6', { metal: 1, rough: 0.25, env: 1.2 });
      const yellow = mat('#F2D24A', { rough: 0.35 });
      const y0 = TABLE;
      const bowl = group(
        lathe([[0, 0], [0.32, 0], [0.36, 0.04], [0.62, 0.32], [0.67, 0.4], [0.63, 0.41], [0.58, 0.35], [0.3, 0.07], [0, 0.07]], porcelain, [0, y0, 0]),
        ring(0.555, 0.014, rim, [0, y0 + 0.25, 0], [Math.PI / 2, 0, 0]),
        ring(0.64, 0.012, rim, [0, y0 + 0.37, 0], [Math.PI / 2, 0, 0]),
        ball(0.52, noodle, [0, y0 + 0.36, 0], [1, 0.55, 1]),
      );
      const rnd = mulberry32(31);
      for (let i = 0; i < 46; i++) {
        const a = rnd() * TAU, r = 0.15 + rnd() * 0.32;
        const n = ring(0.08 + rnd() * 0.12, 0.018, noodle, [Math.cos(a) * r, y0 + 0.36 + 0.29 * Math.sqrt(Math.max(0, 1 - (r / 0.52) ** 2)), Math.sin(a) * r], [Math.PI / 2 + (rnd() - 0.5) * 0.8, rnd() * 0.6, rnd() * TAU], Math.PI * (0.6 + rnd() * 0.6));
        bowl.add(n);
      }
      bowl.add(ball(0.33, sauce, [0.04, y0 + 0.62, 0.02], [1, 0.42, 1]));
      for (let i = 0; i < 16; i++) {
        const a = rnd() * TAU, r = rnd() * 0.24;
        bowl.add(box(0.06, 0.05, 0.06, bits[i % 3], [0.04 + Math.cos(a) * r, y0 + 0.73 - r * 0.3, 0.02 + Math.sin(a) * r], [rnd(), rnd(), rnd()]));
      }
      for (let i = 0; i < 9; i++) bowl.add(box(0.26, 0.022, 0.022, cucumber, [0.02 + (rnd() - 0.5) * 0.12, y0 + 0.77, (rnd() - 0.5) * 0.14], [0, rnd() * TAU, 0]));
      // 젓가락과 단무지 접시
      const sticks = group(rod([-0.75, y0 + 0.02, 0.6], [0.15, y0 + 0.02, 0.78], 0.016, steel), rod([-0.74, y0 + 0.02, 0.66], [0.16, y0 + 0.02, 0.84], 0.016, steel));
      const plate = group(cyl(0.2, 0.17, 0.04, porcelain, [0.55, y0 + 0.02, 0.5]));
      for (let i = 0; i < 3; i++) plate.add(mesh(new T.CylinderGeometry(0.08, 0.08, 0.03, 20, 1, false, 0, Math.PI), yellow, [0.5 + i * 0.06, y0 + 0.06, 0.5 - i * 0.03], [Math.PI / 2, 0, i]));
      const all = group(soban(), bowl, sticks, plate);
      place(scene, all, layout, { fit: 2.05, yaw: 0.4 });
      return () => {};
    },
    fx(w, h, wide, geo) {
      const hy = geo.horizonY;
      const c = document.createElement('canvas'); c.width = w; c.height = h; const cg = c.getContext('2d');
      const strings = [
        ...lanternString(cg, 0, hy * 0.62, w * 0.5, hy * 0.7, h * 0.05, wide ? 9 : 6, h, 'red'),
        ...lanternString(cg, w * 0.5, hy * 0.7, w, hy * 0.6, h * 0.05, wide ? 9 : 6, h, 'red'),
      ];
      const lights = bridge(cg, w * (wide ? 0.55 : 0.5), w * 1.05, hy - h * 0.014, h, [w * (wide ? 0.8 : 0.78)], h * 0.075, '#000');
      const stars = starList(w, hy * 0.6, wide ? 80 : 45, 12);
      const [sx, sy] = spotScreen(geo, wide, incheonBowlY);
      return (g, t) => {
        drawStars(g, stars, t);
        drawBridgeLights(g, lights, h, t, 30);
        drawLanterns(g, strings, h, t);
        steam(g, sx, sy, h, t, 3, 9);
      };
    },
  };

  // ---------------- 광주: 새벽 무등산, 서석대 주상절리, 억새 ----------------

  // 억새 덤불: 가장자리와 앞쪽에 모아 심는다(가운데 단상 앞은 비운다)
  function silverGrass(w, h, hy, wide) {
    const rnd = mulberry32(73);
    const spots = wide ? [[0.03, 0.55], [0.12, 0.8], [0.26, 0.95], [0.04, 0.95], [0.86, 0.6], [0.95, 0.75], [0.9, 0.95], [0.45, 0.35], [0.62, 0.3], [0.2, 0.4]]
      : [[0.05, 0.6], [0.15, 0.9], [0.9, 0.55], [0.95, 0.88], [0.4, 0.3], [0.7, 0.32]];
    const clumps = [];
    spots.forEach(([u, v]) => {
      const x = w * u, y = hy + (h - hy) * v, s = 0.4 + v;
      const n = 7 + Math.floor(rnd() * 5);
      for (let i = 0; i < n; i++) clumps.push({ x: x + (rnd() - 0.5) * h * 0.03 * s, y, H: h * (0.05 + rnd() * 0.04) * s, lean: (i / (n - 1) - 0.5) * 0.9 + (rnd() - 0.5) * 0.2, p: rnd() * TAU, s });
    });
    return clumps;
  }
  function drawGrass(g, list, h, t) {
    list.forEach((s) => {
      const a = s.lean + (t == null ? 0 : Math.sin(t * fq(0.7) + s.x * 0.004) * 0.1);
      const tx = s.x + Math.sin(a) * s.H, ty = s.y - Math.cos(a) * s.H;
      g.strokeStyle = 'rgba(70,58,30,0.85)'; g.lineWidth = Math.max(1, h * 0.0016 * s.s);
      g.beginPath(); g.moveTo(s.x, s.y); g.quadraticCurveTo(s.x + Math.sin(a) * s.H * 0.2, s.y - s.H * 0.6, tx, ty); g.stroke();
      // 은빛 이삭: 끝에서 처지는 깃털 몇 가닥
      for (let k = 0; k < 4; k++) {
        const b = a + 0.35 + k * 0.12, L = s.H * (0.32 - k * 0.04);
        g.strokeStyle = `rgba(240,230,210,${0.55 - k * 0.08})`; g.lineWidth = Math.max(1, h * 0.0022 * s.s);
        g.beginPath(); g.moveTo(tx, ty); g.quadraticCurveTo(tx + Math.sin(b) * L * 0.6, ty - Math.cos(b) * L * 0.6, tx + Math.sin(b + 0.5) * L, ty - Math.cos(b + 0.5) * L * 0.6); g.stroke();
      }
    });
  }
  const gwangju = {
    key: 0xffc9a8,
    paint(g, w, h, wide, geo, animated) {
      const { horizonY: hy, pool } = geo;
      sky(g, w, hy, [[0, '#141C3E'], [0.45, '#4B3F73'], [0.85, '#D98C6A'], [1, '#F2B27A']]);
      bokeh(g, w * (wide ? 0.62 : 0.55), hy, h * 0.28, 0.4, '255,190,130');
      // 무등산: 넓게 퍼진 둥근 산
      g.fillStyle = '#4A3E6C'; g.beginPath(); g.moveTo(0, hy);
      for (let x = 0; x <= w; x += 4) { const u = x / w; g.lineTo(x, hy - h * 0.15 * Math.exp(-(((u - (wide ? 0.55 : 0.5)) / 0.32) ** 2)) - h * 0.02 * Math.sin(u * 17)); }
      g.lineTo(w, hy); g.closePath(); g.fill();
      ridge(g, w, hy + 2, h * 0.05, 9, '#2E2849');
      // 산자락 도시 불빛
      const rnd = mulberry32(3);
      for (let i = 0; i < (wide ? 90 : 50); i++) { g.fillStyle = `rgba(255,${200 + rnd() * 40},150,${0.4 + rnd() * 0.5})`; g.fillRect(rnd() * w, hy - h * 0.004 - rnd() * h * 0.01, 2, 2); }
      vgrad(g, 0, hy - h * 0.05, w, hy, [[0, 'rgba(255,214,190,0)'], [1, 'rgba(255,214,190,0.25)']]);
      ground(g, w, h, hy, [[0, '#6A5432'], [0.35, '#3F321E'], [1, '#120E08']]);
      strokes(g, w, h, hy, 15, 500, ['190,165,110', '40,30,15'], 0.003, 0.01);
      if (!animated) drawGrass(g, silverGrass(w, h, hy, wide), h, null);
      floorPool(g, pool, 'rgba(255,205,150,0.28)', 'rgba(200,160,110,0.10)');
    },
    async build(scene, layout) {
      const rock = mat('#6E6862', { rough: 0.95, flat: true, env: 0.3, bump: stoneTexture(9, '#999', '30,30,30', 30), bumpScale: 0.08 });
      const top = mat('#958D84', { rough: 0.9, flat: true, env: 0.3 });
      const moss = mat('#4F6B35', { rough: 1 });
      const pineM = mat('#2E4A2C', { rough: 0.9 });
      const bark = mat('#4A3222', { rough: 0.9 });
      const cols = [];
      const rnd = mulberry32(21);
      const R = 0.27, dx = R * Math.sqrt(3);
      for (let q = -2; q <= 2; q++) {
        for (let r = -2; r <= 2; r++) {
          if (Math.abs(q + r) > 2) continue;
          const x = dx * (q + r / 2), z = R * 1.5 * r;
          const d = Math.hypot(x, z);
          const H = 2.6 - d * 1.1 + rnd() * 0.5;
          const c = mesh(new T.CylinderGeometry(R, R * 1.02, H, 6), rock, [x, H / 2, z], [(rnd() - 0.5) * 0.06, rnd() * 0.2, (rnd() - 0.5) * 0.06]);
          cols.push(c);
          cols.push(mesh(new T.CylinderGeometry(R * 0.98, R * 0.98, 0.03, 6), top, [x, H + 0.01, z], [0, c.rotation.y, 0]));
        }
      }
      for (let i = 0; i < 6; i++) { const a = rnd() * TAU; cols.push(ball(0.22, moss, [Math.cos(a) * 1.05, 0.08, Math.sin(a) * 1.05], [1.3, 0.4, 1])); }
      const tree = group(cyl(0.05, 0.08, 0.8, bark, [0, 0.4, 0]), cone(0.42, 0.6, pineM, [0, 0.95, 0]), cone(0.34, 0.5, pineM, [0, 1.25, 0]), cone(0.24, 0.42, pineM, [0, 1.52, 0]));
      tree.position.set(1.25, 0, 0.5);
      place(scene, group(cols, tree), layout, { fit: 2.8, yaw: 0.3 });
      return () => {};
    },
    fx(w, h, wide, geo) {
      const hy = geo.horizonY;
      const grass = silverGrass(w, h, hy, wide);
      const cl = clouds(w, h, hy, wide ? 4 : 3, 33, '255,215,200');
      return (g, t) => {
        cl(g, t);
        drawGrass(g, grass, h, t);
        // 산허리 안개가 천천히 오간다
        const x = w * (0.3 + 0.1 * Math.sin(t * fq(0.25)));
        g.save(); g.translate(x, hy - h * 0.03); g.scale(1, 0.12);
        const b = g.createRadialGradient(0, 0, 0, 0, 0, w * 0.4);
        b.addColorStop(0, 'rgba(255,230,215,0.22)'); b.addColorStop(1, 'rgba(255,230,215,0)');
        g.fillStyle = b; g.beginPath(); g.arc(0, 0, w * 0.4, 0, TAU); g.fill(); g.restore();
      };
    },
  };

  // ---------------- 대전: 한빛탑, 엑스포다리, 탐조등 ----------------

  const daejeonTopY = 3.9;
  const daejeon = {
    key: 0xcfe0ff,
    paint(g, w, h, wide, geo, animated) {
      const { horizonY: hy, pool } = geo;
      sky(g, w, hy, [[0, '#050A1E'], [0.6, '#0E1A3E'], [1, '#1E2E60']]);
      if (!animated) drawStars(g, starList(w, hy * 0.75, wide ? 140 : 80, 44));
      skyline(g, 0, w, hy - h * 0.012, h, 17, { color: '#0B1634', maxH: 0.08, lit: '190,215,255', density: 0.28 });
      // 엑스포다리: 강 위 두 아치와 불빛, 강물
      vgrad(g, 0, hy - h * 0.012, w, hy + h * 0.02, [[0, '#0E2248'], [1, '#081530']]);
      const bx = w * (wide ? 0.72 : 0.6), bw = h * 0.3;
      g.strokeStyle = '#E8EEF8'; g.lineWidth = Math.max(1.5, h * 0.003);
      [[-0.5, 0], [0, 0.5]].forEach(([a, b]) => { g.beginPath(); g.moveTo(bx + a * bw, hy - h * 0.008); g.quadraticCurveTo(bx + (a + b) / 2 * bw, hy - h * 0.07, bx + b * bw, hy - h * 0.008); g.stroke(); });
      g.fillStyle = '#C9D6EA'; g.fillRect(bx - bw * 0.5, hy - h * 0.012, bw, h * 0.004);
      bokeh(g, bx, hy - h * 0.03, h * 0.08, 0.25, '130,180,255');
      ground(g, w, h, hy + h * 0.02, [[0, '#1C2A40'], [0.4, '#111A2A'], [1, '#05080F']]);
      paving(g, w, h, hy + h * 0.02, 52, '170,200,255');
      floorPool(g, pool, 'rgba(220,230,255,0.26)', 'rgba(110,150,230,0.10)');
    },
    async build(scene, layout, animated) {
      const metal = mat('#D7DEE8', { metal: 0.85, rough: 0.28, env: 1.2 });
      const concrete = mat('#8E97A3', { rough: 0.8 });
      const blue = mat('#2E7DD8', { emissive: '#4FB3FF', ei: 1.2, rough: 0.3 });
      const red = mat('#FF4040', { emissive: '#FF3030', ei: 2 });
      const lamp = mat('#FFFFFF', { emissive: '#FFF4D8', ei: 1.5 });
      const H = 3.4;
      const parts = [
        cyl(0.75, 0.85, 0.25, concrete, [0, 0.125, 0], null, 48),
        cyl(0.09, 0.17, H, metal, [0, 0.25 + H / 2, 0], null, 24),
        ball(0.4, metal, [0, 2.1, 0]),
        ring(0.52, 0.035, blue, [0, 2.1, 0], [Math.PI / 2 - 0.35, 0, 0.2]),
        ring(0.5, 0.025, blue, [0, 2.1, 0], [Math.PI / 2 + 0.4, 0.3, -0.3]),
        cone(0.07, 0.7, metal, [0, 0.25 + H + 0.3, 0]),
        ball(0.06, red, [0, 0.25 + H + 0.66, 0]),
      ];
      // 세 다리가 위에서 모인다
      [0, 1, 2].forEach((i) => {
        const a = i * TAU / 3;
        const foot = [Math.cos(a) * 0.62, 0.25, Math.sin(a) * 0.62];
        parts.push(rod(foot, [0, 2.9, 0], 0.045, metal));
        for (let k = 1; k <= 5; k++) { const u = k / 6; parts.push(ball(0.035, lamp, [foot[0] * (1 - u), 0.25 + (2.9 - 0.25) * u, foot[2] * (1 - u)])); }
      });
      const tower = group(parts);
      const gp = place(scene, tower, layout, { fit: daejeonTopY, yaw: 0.2 });
      const beacon = sprite(radialTexture([[0, 'rgba(255,90,90,1)'], [0.3, 'rgba(255,60,60,0.4)'], [1, 'rgba(255,60,60,0)']]), 0.9);
      beacon.position.set(0, daejeonTopY - 0.02, 0); beacon.scale.set(0.7, 0.7, 1); gp.add(beacon);
      if (!animated) return () => {};
      return (t) => {
        beacon.material.opacity = 0.2 + 0.8 * Math.max(0, Math.sin(t * fq(1.5)));
        blue.emissiveIntensity = 0.9 + 0.5 * Math.sin(t * fq(0.9));
      };
    },
    fx(w, h, wide, geo) {
      const hy = geo.horizonY;
      const stars = starList(w, hy * 0.75, wide ? 140 : 80, 44);
      const c = document.createElement('canvas'); c.width = w; c.height = h;
      const wins = skyline(c.getContext('2d'), 0, w, hy - h * 0.012, h, 17, { color: '#000', maxH: 0.08, lit: '190,215,255', density: 0.28 });
      const beams = [[0.18, -0.25, 0.2], [0.55, 0.15, 0.7], [0.9, 0.35, 1.3]];
      return (g, t) => {
        drawStars(g, stars, t);
        // 과학 도시의 탐조등이 하늘을 쓴다
        beams.forEach(([u, base, p]) => {
          const x = w * u, a = base + 0.35 * Math.sin(t * fq(0.5) + p);
          const len = hy * 1.2, sp = 0.05;
          const grd = g.createLinearGradient(x, hy, x + Math.sin(a) * len, hy - Math.cos(a) * len);
          grd.addColorStop(0, 'rgba(190,215,255,0.22)'); grd.addColorStop(1, 'rgba(190,215,255,0)');
          g.fillStyle = grd; g.beginPath(); g.moveTo(x, hy);
          g.lineTo(x + Math.sin(a - sp) * len, hy - Math.cos(a - sp) * len); g.lineTo(x + Math.sin(a + sp) * len, hy - Math.cos(a + sp) * len); g.closePath(); g.fill();
        });
        blinkWindows(g, wins, h, '190,215,255', t);
      };
    },
  };

  // ---------------- 경주: 달밤의 대릉원, 첨성대, 신라 금관 ----------------

  function cheomseongdae(g, x, baseY, H, color) {
    g.fillStyle = color;
    g.fillRect(x - H * 0.3, baseY - H * 0.06, H * 0.6, H * 0.06);
    g.beginPath(); g.moveTo(x - H * 0.24, baseY - H * 0.06);
    g.bezierCurveTo(x - H * 0.26, baseY - H * 0.45, x - H * 0.12, baseY - H * 0.7, x - H * 0.12, baseY - H * 0.9);
    g.lineTo(x + H * 0.12, baseY - H * 0.9);
    g.bezierCurveTo(x + H * 0.12, baseY - H * 0.7, x + H * 0.26, baseY - H * 0.45, x + H * 0.24, baseY - H * 0.06); g.closePath(); g.fill();
    g.fillRect(x - H * 0.15, baseY - H, H * 0.3, H * 0.1);
    g.fillStyle = 'rgba(40,30,20,0.5)'; g.fillRect(x - H * 0.05, baseY - H * 0.55, H * 0.1, H * 0.1);
    g.strokeStyle = 'rgba(80,60,30,0.35)'; g.lineWidth = 1;
    for (let k = 1; k < 14; k++) { const y = baseY - H * 0.06 - k * H * 0.06; g.beginPath(); g.moveTo(x - H * 0.3, y); g.lineTo(x + H * 0.3, y); g.stroke(); }
  }
  const gyeongju = {
    key: 0xfff0d0,
    paint(g, w, h, wide, geo, animated) {
      const { horizonY: hy, pool } = geo;
      sky(g, w, hy, [[0, '#08102A'], [0.6, '#17244C'], [1, '#2E3864']]);
      if (!animated) drawStars(g, starList(w, hy * 0.7, wide ? 120 : 70, 77));
      moon(g, w * (wide ? 0.85 : 0.8), hy * 0.28, h * 0.035);
      // 대릉원 고분: 매끈한 둥근 봉분
      [[0.12, 0.13, 0.05], [0.3, 0.1, 0.035], [0.5, 0.16, 0.06], [0.95, 0.12, 0.045]].forEach(([u, rw, rh], i) => {
        g.fillStyle = ['#183424', '#1C3A28', '#14301F', '#183424'][i];
        g.beginPath(); g.ellipse(w * u, hy + 2, h * rw * (wide ? 1.4 : 1), h * rh, 0, Math.PI, 0); g.fill();
        g.strokeStyle = 'rgba(160,200,170,0.18)'; g.lineWidth = 2;
        g.beginPath(); g.ellipse(w * u, hy + 2, h * rw * (wide ? 1.4 : 1), h * rh, 0, Math.PI * 1.15, Math.PI * 1.6); g.stroke();
      });
      // 조명 받은 첨성대
      const cx = w * (wide ? 0.72 : 0.68);
      bokeh(g, cx, hy - h * 0.04, h * 0.1, 0.35, '255,200,120');
      cheomseongdae(g, cx, hy + h * 0.005, h * 0.11, '#C9A66B');
      ground(g, w, h, hy, [[0, '#1E3A26'], [0.4, '#132817'], [1, '#050C07']]);
      strokes(g, w, h, hy, 8, 600, ['120,170,120', '10,25,12'], 0.004, 0.014);
      floorPool(g, pool, 'rgba(255,220,160,0.28)', 'rgba(150,200,160,0.10)');
    },
    async build(scene, layout, animated) {
      const gold = mat('#F0B64A', { metal: 1, rough: 0.18, env: 1.5 });
      const jade = mat('#2FA36B', { rough: 0.12, env: 1.2 });
      const velvet = mat('#7E1626', { rough: 0.95 });
      const stoneM = mat('#6E675C', { rough: 0.8 });
      const base = group(
        box(1.5, 0.5, 1.5, stoneM, [0, 0.25, 0]),
        box(1.6, 0.06, 1.6, stoneM, [0, 0.53, 0]),
        cyl(0.82, 0.86, 0.2, velvet, [0, 0.66, 0], null, 48),
        ball(0.82, velvet, [0, 0.76, 0], [1, 0.12, 1]),
      );
      [[1, 1], [1, -1], [-1, 1], [-1, -1]].forEach(([a, b]) => base.add(ball(0.06, gold, [a * 0.72, 0.58, b * 0.72])));
      const y0 = 0.86, R = 0.55;
      const crown = group(mesh(new T.CylinderGeometry(R, R, 0.18, 64, 1, true), mat('#F0B64A', { metal: 1, rough: 0.18, env: 1.5, double: true }), [0, y0 + 0.09, 0]));
      const spangles = [];
      const rnd = mulberry32(13);
      const spangle = (x, y, z) => {
        const s = mesh(new T.CylinderGeometry(0.04, 0.04, 0.006, 16), gold, [x, y, z], [Math.PI / 2, 0, 0]);
        spangles.push({ s, p: rnd() * TAU }); return s;
      };
      // 띠 둘레 달개
      for (let i = 0; i < 24; i++) { const a = (i / 24) * TAU; crown.add(spangle(Math.sin(a) * (R + 0.02), y0 + 0.05, Math.cos(a) * (R + 0.02))); }
      // 出 자 세움 장식 셋(앞)과 사슴뿔 장식 둘(뒤)
      const upright = (a, antler) => {
        const u = new T.Group();
        const H = antler ? 0.9 : 1.05;
        u.add(box(0.07, H, 0.015, gold, [0, H / 2, 0]));
        (antler ? [0.3, 0.55, 0.75] : [0.35, 0.65, 0.92]).forEach((hh, k) => {
          [-1, 1].forEach((sd) => {
            const len = antler ? 0.22 - k * 0.04 : 0.2;
            u.add(box(len, 0.05, 0.015, gold, [sd * len / 2, hh, 0]));
            u.add(box(0.05, antler ? 0.16 : 0.2, 0.015, gold, [sd * len, hh + (antler ? 0.08 : 0.1), 0]));
            u.add(spangle(sd * len, hh - 0.06, 0.02));
          });
        });
        u.add(ball(0.06, gold, [0, H + 0.03, 0], [1, 1.2, 0.35]));
        for (let k = 0; k < 4; k++) u.add(spangle((rnd() - 0.5) * 0.06, 0.15 + k * 0.22, 0.02));
        if (!antler) {
          [0.5, 0.8].forEach((hh) => [-1, 1].forEach((sd) => u.add(ring(0.035, 0.022, jade, [sd * 0.2, hh - 0.12, 0.03], [0, 0, sd > 0 ? 0.5 : 2.6], Math.PI * 1.3))));
        }
        u.position.set(Math.sin(a) * R, y0 + 0.16, Math.cos(a) * R); u.rotation.y = a;
        return u;
      };
      [-0.75, 0, 0.75].forEach((a) => crown.add(upright(a, false)));
      [Math.PI - 0.9, Math.PI + 0.9].forEach((a) => crown.add(upright(a, true)));
      // 양옆 드리개
      [-1, 1].forEach((sd) => {
        const x = sd * (R + 0.03);
        for (let k = 0; k < 5; k++) crown.add(ball(0.025, gold, [x, y0 - 0.02 - k * 0.07, 0.05]));
        crown.add(ring(0.045, 0.025, jade, [x, y0 - 0.4, 0.05], [0, 0, 1.2], Math.PI * 1.3));
      });
      place(scene, group(base, crown), layout, { fit: 2.6, yaw: 0.35 });
      if (!animated) return () => {};
      return (t) => spangles.forEach((k) => { k.s.rotation.z = 0.5 * Math.sin(t * fq(1.3) + k.p); });
    },
    fx(w, h, wide, geo) {
      const hy = geo.horizonY;
      const stars = starList(w, hy * 0.7, wide ? 120 : 70, 77);
      const flies = floaters(w, hy - h * 0.02, h * 0.85, wide ? 24 : 14, 4);
      return (g, t) => { drawStars(g, stars, t); drawFloaters(g, flies, h, '230,255,170', t); };
    },
  };

  // ---------------- 전주: 한옥마을 청사초롱, 돌솥비빔밥 ----------------

  const jeonjuBowlY = 1.85;
  const jeonju = {
    key: 0xffd2b0,
    paint(g, w, h, wide, geo, animated) {
      const { horizonY: hy, pool } = geo;
      sky(g, w, hy, [[0, '#1A1C44'], [0.5, '#4E3A6C'], [0.85, '#C9706A'], [1, '#E99A6A']]);
      bokeh(g, w * 0.4, hy, h * 0.25, 0.3, '255,170,120');
      ridge(g, w, hy - h * 0.02, h * 0.06, 30, '#43345E');
      hanokRoofs(g, w, hy - h * 0.005, h, 6, '#231A33', wide ? 11 : 7);
      hanokRoofs(g, w, hy + h * 0.02, h, 9, '#171124', wide ? 8 : 5);
      const strings = [
        ...lanternString(g, -w * 0.05, hy - h * 0.04, w * 0.55, hy - h * 0.02, h * 0.04, wide ? 10 : 6, h, 'blue'),
        ...lanternString(g, w * 0.5, hy - h * 0.025, w * 1.05, hy - h * 0.05, h * 0.04, wide ? 9 : 6, h, 'blue'),
      ];
      if (!animated) drawLanterns(g, strings, h, null);
      ground(g, w, h, hy + h * 0.025, [[0, '#4C3A2A'], [0.4, '#2E2218'], [1, '#100B07']]);
      strokes(g, w, h, hy + h * 0.025, 26, 400, ['200,170,130', '30,20,10'], 0.002, 0.006);
      floorPool(g, pool, 'rgba(255,200,140,0.30)', 'rgba(200,140,100,0.10)');
    },
    async build(scene, layout) {
      const pot = mat('#2A2A2A', { rough: 0.7, bump: stoneTexture(4, '#777', '20,20,20', 40), bumpScale: 0.05 });
      const wood = mat('#6A4426', { rough: 0.6 });
      const rice = mat('#F3EEDF', { rough: 0.7 });
      const brass = mat('#D9A945', { metal: 1, rough: 0.25, env: 1.3 });
      const T0 = [
        mat('#2F6B2A'), mat('#E8782A'), mat('#F0E6B4'), mat('#6B4426'), mat('#8A5A36'), mat('#7A1E1E'), mat('#9AC468'),
      ];
      const y0 = TABLE;
      const bowl = group(
        cyl(0.68, 0.7, 0.05, wood, [0, y0 + 0.025, 0], null, 48),
        lathe([[0, 0], [0.45, 0], [0.55, 0.08], [0.6, 0.38], [0.62, 0.42], [0.55, 0.42], [0.52, 0.12], [0, 0.12]], pot, [0, y0 + 0.05, 0]),
        ball(0.53, rice, [0, y0 + 0.43, 0], [1, 0.28, 1]),
      );
      const rnd = mulberry32(17);
      // 일곱 가지 나물을 부채꼴로 돌려 담는다
      T0.forEach((m, i) => {
        const a0 = (i / 7) * TAU;
        for (let k = 0; k < 9; k++) {
          const a = a0 + (rnd() * 0.8 + 0.1) * (TAU / 7), r = 0.2 + rnd() * 0.26;
          const y = y0 + 0.44 + 0.15 * Math.sqrt(Math.max(0, 1 - (r / 0.53) ** 2)) + rnd() * 0.02;
          const piece = i === 2 ? cyl(0.012, 0.012, 0.16, m, [Math.cos(a) * r, y, Math.sin(a) * r], [Math.PI / 2, 0, a]) // 콩나물
            : i === 4 ? ball(0.05, m, [Math.cos(a) * r, y, Math.sin(a) * r], [1, 0.4, 1]) // 버섯
              : box(i === 5 ? 0.07 : 0.16, 0.025, i === 5 ? 0.06 : 0.025, m, [Math.cos(a) * r, y, Math.sin(a) * r], [0, -a + (rnd() - 0.5), 0]);
          bowl.add(piece);
        }
      });
      // 가운데 고추장과 달걀
      bowl.add(ball(0.2, mat('#FFFFFF', { rough: 0.5 }), [0.04, y0 + 0.6, 0.02], [1.1, 0.12, 0.95]));
      bowl.add(ball(0.085, mat('#F5B21B', { rough: 0.25, env: 0.8 }), [0.04, y0 + 0.63, 0.02], [1, 0.6, 1]));
      bowl.add(ball(0.08, mat('#B3191A', { rough: 0.3 }), [-0.16, y0 + 0.6, -0.1], [1, 0.7, 1]));
      // 놋숟가락과 젓가락, 김치 종지
      const spoon = group(rod([-0.8, y0 + 0.03, 0.55], [-0.2, y0 + 0.03, 0.72], 0.02, brass), ball(0.09, brass, [-0.86, y0 + 0.035, 0.53], [1.2, 0.25, 0.9]));
      const sticks = group(rod([-0.75, y0 + 0.02, 0.66], [-0.05, y0 + 0.02, 0.84], 0.013, brass), rod([-0.73, y0 + 0.02, 0.71], [-0.03, y0 + 0.02, 0.89], 0.013, brass));
      const kimchi = group(cyl(0.16, 0.12, 0.08, mat('#F2EEE4', { rough: 0.3 }), [0.6, y0 + 0.04, 0.45]), ball(0.13, mat('#C83A22', { rough: 0.5 }), [0.6, y0 + 0.09, 0.45], [1, 0.45, 1]));
      place(scene, group(soban(), bowl, spoon, sticks, kimchi), layout, { fit: 1.95, yaw: 0.4 });
      return () => {};
    },
    fx(w, h, wide, geo) {
      const hy = geo.horizonY;
      const c = document.createElement('canvas'); c.width = w; c.height = h; const cg = c.getContext('2d');
      const strings = [
        ...lanternString(cg, -w * 0.05, hy - h * 0.04, w * 0.55, hy - h * 0.02, h * 0.04, wide ? 10 : 6, h, 'blue'),
        ...lanternString(cg, w * 0.5, hy - h * 0.025, w * 1.05, hy - h * 0.05, h * 0.04, wide ? 9 : 6, h, 'blue'),
      ];
      const [sx, sy] = spotScreen(geo, wide, jeonjuBowlY);
      const cl = clouds(w, h, hy, wide ? 4 : 3, 71, '255,200,190');
      return (g, t) => { cl(g, t); drawLanterns(g, strings, h, t); steam(g, sx, sy, h, t, 3, 4); };
    },
  };

  // ---------------- 강릉: 안목해변 해돋이, 송림, 커피 한 잔 ----------------

  const gangneungCupY = 1.8;
  const gangneung = {
    key: 0xffe0b8,
    paint(g, w, h, wide, geo, animated) {
      const { horizonY: hy, pool, toScreen } = geo;
      const shoreY = toScreen(0, 0, -9.5)[1];
      const sunX = w * (wide ? 0.62 : 0.55);
      sky(g, w, hy, [[0, '#1C2B55'], [0.4, '#5E6494'], [0.8, '#F0A070'], [1, '#FFD08A']]);
      bokeh(g, sunX, hy, h * 0.35, 0.5, '255,200,120');
      g.fillStyle = '#FFE7B0'; g.beginPath(); g.arc(sunX, hy, h * 0.04, Math.PI, 0); g.fill();
      seaBand(g, w, hy, shoreY, h, 9, '#4E5C88', '#2E4470');
      // 해 아래 금빛 물길
      const glints = seaBand(document.createElement('canvas').getContext('2d'), w, hy, shoreY, h, 10, '#000', '#000', null, sunX);
      if (!animated) drawGlints(g, glints, h, '255,220,150', null);
      // 경포대 언덕과 송림
      g.fillStyle = '#18202A'; g.beginPath(); g.ellipse(w * 0.08, hy + 2, h * 0.14, h * 0.05, 0, Math.PI, 0); g.fill();
      drawHall(g, w * 0.08, hy - h * 0.03, h * 0.05, h * 0.03);
      ground(g, w, h, shoreY, [[0, '#8A6E52'], [0.3, '#5A4634'], [1, '#1A1410']]);
      g.strokeStyle = 'rgba(255,245,230,0.5)'; g.lineWidth = h * 0.004; g.beginPath();
      for (let x = 0; x <= w; x += 6) { const y = shoreY + Math.sin(x / w * 19) * h * 0.003; if (x === 0) g.moveTo(x, y); else g.lineTo(x, y); }
      g.stroke();
      const rnd = mulberry32(5);
      for (let i = 0; i < (wide ? 6 : 4); i++) pine(g, w * (wide ? 0.92 : 0.9) + i * h * 0.04 - h * 0.08, shoreY + h * 0.06 + rnd() * h * 0.05, h * (0.25 + rnd() * 0.1), '#141A1E', i + 3);
      pine(g, w * 0.02, shoreY + h * 0.08, h * 0.3, '#141A1E', 9);
      floorPool(g, pool, 'rgba(255,220,170,0.28)', 'rgba(220,180,140,0.10)');
    },
    async build(scene, layout) {
      const cupM = mat('#F6F3EC', { rough: 0.2, env: 0.9 });
      const coffee = mat('#3E2311', { rough: 0.12, env: 1.0 });
      const crema = mat('#E9D3AE', { rough: 0.4 });
      const bean = mat('#3B2212', { rough: 0.35, env: 0.6 });
      const y0 = TABLE;
      const cup = group(
        lathe([[0, 0], [0.42, 0], [0.45, 0.02], [0.42, 0.05], [0, 0.05]], cupM, [0, y0, 0]),
        lathe([[0, 0], [0.22, 0], [0.24, 0.03], [0.34, 0.5], [0.36, 0.54], [0.33, 0.54], [0.31, 0.5], [0.21, 0.06], [0, 0.06]], cupM, [0, y0 + 0.05, 0]),
        ring(0.13, 0.035, cupM, [0.42, y0 + 0.35, 0], [0, 0, -Math.PI / 2], Math.PI),
        cyl(0.315, 0.315, 0.01, coffee, [0, y0 + 0.52, 0], null, 48),
        // 하트 라테 아트
        ball(0.07, crema, [-0.05, y0 + 0.526, -0.04], [1, 0.05, 1]), ball(0.07, crema, [0.05, y0 + 0.526, -0.04], [1, 0.05, 1]),
        mesh(new T.ConeGeometry(0.1, 0.16, 3), crema, [0, y0 + 0.527, 0.06], [Math.PI / 2, 0, Math.PI], [1, 1, 0.05]),
      );
      const rnd = mulberry32(2);
      for (let i = 0; i < 12; i++) cup.add(ball(0.05, bean, [-0.55 + rnd() * 0.3, y0 + 0.03, 0.2 + rnd() * 0.4], [1, 0.6, 1.4]));
      // 핸드드립 주전자
      const steel = mat('#C8CDD4', { metal: 1, rough: 0.25, env: 1.2 });
      const kettle = group(
        lathe([[0, 0], [0.2, 0], [0.22, 0.25], [0.12, 0.38], [0, 0.4]], steel, [0, 0, 0]),
        rod([0.18, 0.08, 0], [0.42, 0.35, 0], 0.025, steel),
        ring(0.1, 0.022, mat('#2A1E16', { rough: 0.6 }), [-0.22, 0.22, 0], [0, 0, Math.PI / 2], Math.PI),
      );
      kettle.position.set(0.55, y0, -0.3); kettle.rotation.y = 2.4;
      place(scene, group(soban(), cup, kettle), layout, { fit: 1.85, yaw: 0.45 });
      return () => {};
    },
    fx(w, h, wide, geo) {
      const { horizonY: hy, toScreen } = geo;
      const shoreY = toScreen(0, 0, -9.5)[1];
      const sunX = w * (wide ? 0.62 : 0.55);
      const glints = seaBand(document.createElement('canvas').getContext('2d'), w, hy, shoreY, h, 10, '#000', '#000', null, sunX);
      const [sx, sy] = spotScreen(geo, wide, gangneungCupY);
      const rnd = mulberry32(6);
      const birds = Array.from({ length: 3 }, () => ({ y: hy * (0.4 + rnd() * 0.4), p: rnd(), s: 0.7 + rnd() * 0.5 }));
      return (g, t) => {
        drawGlints(g, glints, h, '255,220,150', t);
        // 물가로 밀려오는 파도
        for (let k = 0; k < 2; k++) {
          const u = cyc(t, 2, k * 0.5), y = shoreY - (1 - u) * h * 0.02;
          g.strokeStyle = `rgba(255,248,235,${0.4 * Math.sin(Math.PI * u)})`; g.lineWidth = h * 0.003; g.beginPath();
          for (let x = 0; x <= w; x += 8) { const yy = y + Math.sin(x / w * 17 + k) * h * 0.003; if (x === 0) g.moveTo(x, yy); else g.lineTo(x, yy); }
          g.stroke();
        }
        steam(g, sx, sy, h, t, 2, 7);
        birds.forEach((b) => {
          const u = cyc(t, 1, b.p), x = -w * 0.1 + u * w * 1.2, y = b.y + Math.sin(u * TAU * 3) * h * 0.01;
          const f = Math.sin(t * fq(3) + b.p * 9) * 0.5, s = h * 0.012 * b.s;
          g.strokeStyle = 'rgba(30,30,45,0.7)'; g.lineWidth = Math.max(1, h * 0.0022);
          g.beginPath(); g.moveTo(x - s, y - s * f); g.quadraticCurveTo(x - s * 0.4, y - s * 0.6, x, y); g.quadraticCurveTo(x + s * 0.4, y - s * 0.6, x + s, y - s * f); g.stroke();
        });
      };
    },
  };

  // ---------------- 제주: 한라산과 성산일출봉, 유채꽃, 돌하르방 ----------------

  // 유채꽃 무리: 단상 앞은 비우고 양옆과 먼 밭에 모은다
  function canola(w, h, hy, wide) {
    const rnd = mulberry32(64);
    const patches = wide ? [[0.08, 0.5], [0.2, 0.85], [0.05, 0.95], [0.88, 0.5], [0.95, 0.85], [0.35, 0.25], [0.62, 0.22], [0.78, 0.3]]
      : [[0.1, 0.55], [0.08, 0.92], [0.9, 0.5], [0.94, 0.9], [0.5, 0.22]];
    const list = [];
    patches.forEach(([u, v]) => {
      const cx = w * u, cy = hy + (h - hy) * v * v, s = 0.3 + v;
      for (let i = 0; i < 60; i++) {
        const a = rnd() * TAU, r = Math.sqrt(rnd());
        list.push({ x: cx + Math.cos(a) * r * h * 0.09 * s, y: cy + Math.sin(a) * r * h * 0.025 * s, r: h * (0.0018 + 0.003 * rnd()) * s, p: rnd() * TAU });
      }
    });
    return list.sort((a, b) => a.y - b.y);
  }
  function drawCanola(g, list, h, t) {
    list.forEach((f) => {
      const dx = t == null ? 0 : Math.sin(t * fq(0.8) + f.x * 0.01) * f.r * 0.8;
      g.fillStyle = 'rgba(60,90,40,0.8)'; g.fillRect(f.x - f.r * 0.15, f.y, f.r * 0.3, f.r * 3);
      g.fillStyle = 'rgba(250,214,60,0.9)'; g.beginPath(); g.arc(f.x + dx, f.y, f.r, 0, TAU); g.fill();
    });
  }
  const jeju = {
    key: 0xffd6b0,
    paint(g, w, h, wide, geo, animated) {
      const { horizonY: hy, pool } = geo;
      sky(g, w, hy, [[0, '#22305E'], [0.5, '#6A5A8C'], [0.85, '#E7926A'], [1, '#F7B57A']]);
      bokeh(g, w * 0.3, hy, h * 0.25, 0.3, '255,190,130');
      // 한라산: 완만하게 넓은 산과 흰 정상
      const mx = w * (wide ? 0.42 : 0.4);
      g.fillStyle = '#3C3F66'; g.beginPath(); g.moveTo(-w * 0.1, hy);
      for (let x = -w * 0.1; x <= w * 1.1; x += 4) { const u = (x - mx) / (w * 0.45); g.lineTo(x, hy - h * 0.13 / (1 + 2.6 * u * u)); }
      g.lineTo(w * 1.1, hy); g.closePath(); g.fill();
      g.fillStyle = 'rgba(240,240,255,0.55)'; g.beginPath(); g.ellipse(mx, hy - h * 0.128, h * 0.035, h * 0.008, 0, Math.PI, 0); g.fill();
      // 성산일출봉: 바다 위 평평한 분화구
      const sx = w * (wide ? 0.88 : 0.86);
      vgrad(g, w * 0.62, hy - h * 0.006, w * 0.38, hy + h * 0.004, [[0, '#4A5A88'], [1, '#3A4878']]);
      g.fillStyle = '#2A2C48'; g.beginPath(); g.moveTo(sx - h * 0.09, hy); g.lineTo(sx - h * 0.06, hy - h * 0.045); g.lineTo(sx - h * 0.045, hy - h * 0.05);
      g.lineTo(sx + h * 0.04, hy - h * 0.05); g.lineTo(sx + h * 0.055, hy - h * 0.045); g.lineTo(sx + h * 0.08, hy); g.closePath(); g.fill();
      ground(g, w, h, hy, [[0, '#405A30'], [0.4, '#2C4022'], [1, '#0E160B']]);
      // 돌담: 현무암 덩이 줄
      const rnd = mulberry32(12);
      [0.03, 0.08].forEach((dy) => {
        const y = hy + h * dy, s = h * (0.006 + dy * 0.12);
        for (let x = 0; x < w; x += s * 1.6) { g.fillStyle = `rgb(${40 + rnd() * 20},${40 + rnd() * 18},${44 + rnd() * 18})`; g.beginPath(); g.ellipse(x, y - s * 0.6, s, s * 0.7, rnd(), 0, TAU); g.fill(); }
      });
      if (!animated) drawCanola(g, canola(w, h, hy, wide), h, null);
      floorPool(g, pool, 'rgba(255,214,160,0.28)', 'rgba(200,200,110,0.10)');
    },
    async build(scene, layout, animated) {
      const tex = stoneTexture(21, '#9A9690', '30,30,30', 160);
      const basalt = mat('#6A6662', { rough: 1, map: tex, bump: tex, bumpScale: 0.15, env: 0.25 });
      const leaf = mat('#2F5E2A', { rough: 0.8 });
      const orange = mat('#F08A1C', { rough: 0.35, env: 0.8 });
      const bark = mat('#5A3A22', { rough: 0.9 });
      const hb = group(
        box(1.0, 0.3, 0.8, basalt, [0, 0.15, 0]),
        cyl(0.4, 0.46, 1.15, basalt, [0, 0.875, 0], null, 32),
        ball(0.44, basalt, [0, 1.45, 0], [1, 0.45, 0.95]),
        ball(0.4, basalt, [0, 1.92, 0], [1, 1.2, 1]),
        cyl(0.44, 0.46, 0.07, basalt, [0, 2.36, 0], null, 40),
        ball(0.3, basalt, [0, 2.4, 0], [1, 0.75, 1]),
        ball(0.1, basalt, [-0.15, 2.0, 0.34]), ball(0.1, basalt, [0.15, 2.0, 0.34]),
        ring(0.1, 0.024, basalt, [-0.15, 2.0, 0.37], null), ring(0.1, 0.024, basalt, [0.15, 2.0, 0.37], null),
        ball(0.12, basalt, [0, 1.86, 0.4], [0.85, 1.45, 0.9]),
        ring(0.1, 0.02, basalt, [0, 1.68, 0.36], [0, 0, Math.PI], Math.PI),
        // 배 위의 두 손(한 손이 더 높다)
        ball(0.13, basalt, [0.06, 1.2, 0.42], [2.2, 0.75, 0.85]),
        ball(0.13, basalt, [-0.06, 0.92, 0.44], [2.2, 0.75, 0.85]),
      );
      // 귤나무
      const tree = group(cyl(0.06, 0.09, 0.6, bark, [0, 0.3, 0], null, 10), ball(0.42, leaf, [0, 0.85, 0]), ball(0.32, leaf, [0.25, 0.75, 0.15]), ball(0.3, leaf, [-0.25, 0.8, 0.1]));
      const rnd = mulberry32(8);
      for (let i = 0; i < 12; i++) { const a = rnd() * TAU, e = rnd() * 0.9 - 0.3; tree.add(ball(0.07, orange, [Math.cos(a) * 0.42 * Math.cos(e), 0.85 + Math.sin(e) * 0.4, Math.abs(Math.sin(a)) * 0.42 * Math.cos(e) + 0.05])); }
      tree.position.set(0.95, 0, 0.35);
      place(scene, group(hb, tree), layout, { fit: 2.55, yaw: 0.45 });
      return () => {};
    },
    fx(w, h, wide, geo) {
      const hy = geo.horizonY;
      const flowers = canola(w, h, hy, wide);
      const cl = clouds(w, h, hy, wide ? 5 : 3, 19, '255,215,200');
      return (g, t) => { cl(g, t); drawCanola(g, flowers, h, t); };
    },
  };

  // ---------------- 여수: 밤바다, 돌산대교, 거북선 ----------------

  const yeosuShip = { landscape: [-5.4, 0, -15], portrait: [-2.2, 0, -16] };
  const yeosu = {
    key: 0xe0d8ff,
    paint(g, w, h, wide, geo, animated) {
      const { horizonY: hy, pool, toScreen } = geo;
      const shoreY = toScreen(0, 0, -9.5)[1];
      sky(g, w, hy, [[0, '#0A0A26'], [0.6, '#1E1846'], [1, '#3A2456']]);
      if (!animated) drawStars(g, starList(w, hy * 0.6, wide ? 100 : 60, 92));
      moon(g, w * (wide ? 0.2 : 0.18), hy * 0.32, h * 0.024);
      // 건너편 항구 불빛
      g.fillStyle = '#100C26'; ridge(g, w, hy, h * 0.03, 41, '#120E2C');
      skyline(g, 0, w * 0.5, hy, h, 33, { color: '#120E2C', maxH: 0.04, lit: '255,200,140', density: 0.4 });
      seaBand(g, w, hy, shoreY, h, 2, '#0E1034', '#1A2050');
      const lights = bridge(g, w * (wide ? 0.5 : 0.42), w * 1.05, hy - h * 0.016, h, [w * (wide ? 0.78 : 0.75)], h * 0.085, '#16123A');
      if (!animated) drawBridgeLights(g, lights, h, null, 280);
      const refl = reflections(wide ? 30 : 18, w, 0, w, hy, shoreY, 15);
      if (!animated) drawReflections(g, refl, h, null);
      // 앞쪽 나무 데크
      ground(g, w, h, shoreY, [[0, '#2C2430'], [1, '#0C0A0E']]);
      g.strokeStyle = 'rgba(0,0,0,0.35)'; g.lineWidth = 2;
      for (let k = 1; k < 14; k++) { const y = shoreY + (h - shoreY) * (k / 14) ** 1.6; g.beginPath(); g.moveTo(0, y); g.lineTo(w, y); g.stroke(); }
      floorPool(g, pool, 'rgba(255,210,170,0.26)', 'rgba(160,120,220,0.10)');
    },
    async build(scene, layout, animated) {
      const wood = mat('#6E4B2E', { rough: 0.75 });
      const shell = mat('#2F3438', { rough: 0.45, metal: 0.5, env: 0.8 });
      const spikeM = mat('#A8AEB4', { metal: 0.9, rough: 0.3, env: 1.1 });
      const red = mat('#A32C24', { rough: 0.5 });
      const gold = mat('#C8963A', { metal: 0.6, rough: 0.35, env: 1 });
      const sail = mat('#CDB98E', { rough: 0.9 });
      const occluder = new T.Mesh(new T.PlaneGeometry(400, 400), new T.MeshBasicMaterial({ colorWrite: false }));
      occluder.rotation.x = -Math.PI / 2; occluder.position.set(0, 0, -50 - 9.5); occluder.renderOrder = -1;
      occluder.scale.set(1, 0.25, 1); scene.add(occluder);
      // 배 몸통: 위에서 본 윤곽을 세운다(뱃머리가 +x)
      const outline = new T.Shape();
      outline.moveTo(-1.5, -0.5); outline.lineTo(1.1, -0.55); outline.quadraticCurveTo(1.6, -0.3, 1.7, 0); outline.quadraticCurveTo(1.6, 0.3, 1.1, 0.55);
      outline.lineTo(-1.5, 0.5); outline.lineTo(-1.6, 0); outline.closePath();
      const hullGeo = new T.ExtrudeGeometry(outline, { depth: 0.6, bevelEnabled: true, bevelThickness: 0.05, bevelSize: 0.05, bevelSegments: 2 });
      const hull = mesh(hullGeo, wood, [0, -0.25, 0], [-Math.PI / 2, 0, 0]);
      const ship = group(
        hull,
        box(3.0, 0.08, 1.12, red, [-0.15, 0.32, 0]),
        mesh(new T.SphereGeometry(1, 32, 16, 0, TAU, 0, Math.PI / 2), shell, [-0.1, 0.36, 0], null, [1.45, 0.55, 0.56]),
      );
      const rnd = mulberry32(3);
      for (let i = 0; i < 70; i++) {
        const th = rnd() * TAU, ph = rnd() * 1.2;
        const v = new T.Vector3(Math.sin(ph) * Math.cos(th) * 1.45, Math.cos(ph) * 0.55, Math.sin(ph) * Math.sin(th) * 0.56);
        const n = new T.Vector3(v.x / 2.1, v.y / 0.3, v.z / 0.31).normalize();
        const s = cone(0.03, 0.12, spikeM, [v.x - 0.1, v.y + 0.36, v.z]);
        s.quaternion.setFromUnitVectors(new T.Vector3(0, 1, 0), n); ship.add(s);
      }
      // 용머리
      ship.add(rod([1.55, 0.3, 0], [1.9, 0.7, 0], 0.09, gold));
      ship.add(ball(0.17, gold, [1.97, 0.78, 0], [1.3, 0.9, 0.9]));
      ship.add(cone(0.08, 0.2, red, [2.15, 0.74, 0], [0, 0, -Math.PI / 2]));
      ship.add(ball(0.035, mat('#FFFFFF'), [2.03, 0.86, 0.1]), ball(0.035, mat('#FFFFFF'), [2.03, 0.86, -0.1]));
      // 노와 돛대
      for (let i = 0; i < 7; i++) [-1, 1].forEach((sd) => ship.add(rod([-1.1 + i * 0.32, 0.2, sd * 0.55], [-1.25 + i * 0.32, -0.3, sd * 1.05], 0.025, wood)));
      [-0.6, 0.5].forEach((x) => { ship.add(cyl(0.035, 0.04, 1.3, wood, [x, 1.1, 0])); ship.add(cyl(0.07, 0.07, 0.5, sail, [x, 1.5, 0], [Math.PI / 2, 0, 0])); });
      [-0.6, 0.5].forEach((x, i) => ship.add(mesh(new T.PlaneGeometry(0.3, 0.18), mat(i ? '#2F5FB0' : '#C8302A', { double: true }), [x + 0.15, 1.7, 0])));
      const gp = new T.Group(); gp.add(ship);
      const lay = layout === 'landscape' ? 'landscape' : 'portrait';
      place(scene, gp, layout, { fit: 2.7, yaw: lay === 'landscape' ? 0.55 : 0.9, at: yeosuShip[lay] });
      gp.position.y -= 0.28;
      const y0 = gp.position.y;
      if (!animated) return () => {};
      return (t) => {
        gp.position.y = y0 + 0.05 * Math.sin(t * fq(1.1));
        gp.rotation.x = 0.03 * Math.sin(t * fq(0.9));
        gp.rotation.z = 0.02 * Math.sin(t * fq(1.1) + 1);
      };
    },
    fx(w, h, wide, geo) {
      const { horizonY: hy, toScreen } = geo;
      const shoreY = toScreen(0, 0, -9.5)[1];
      const c = document.createElement('canvas'); c.width = w; c.height = h; const cg = c.getContext('2d');
      const lights = bridge(cg, w * (wide ? 0.5 : 0.42), w * 1.05, hy - h * 0.016, h, [w * (wide ? 0.78 : 0.75)], h * 0.085, '#000');
      const refl = reflections(wide ? 30 : 18, w, 0, w, hy, shoreY, 15);
      const stars = starList(w, hy * 0.6, wide ? 100 : 60, 92);
      return (g, t) => { drawStars(g, stars, t); drawReflections(g, refl, h, t); drawBridgeLights(g, lights, h, t, 280); };
    },
  };

  // ---------------- 안동: 하회마을, 부용대 줄불놀이, 하회탈 ----------------

  function andongLayout(w, h, hy, wide) {
    const cliffX = w * (wide ? 0.74 : 0.7);
    return { cliffX, top: [cliffX + h * 0.02, hy - h * 0.15], ropes: [[w * 0.05, hy + h * 0.01], [w * 0.22, hy + h * 0.012], [w * 0.4, hy + h * 0.014]] };
  }
  const andong = {
    key: 0xffd8b0,
    paint(g, w, h, wide, geo, animated) {
      const { horizonY: hy, pool } = geo;
      const L = andongLayout(w, h, hy, wide);
      sky(g, w, hy, [[0, '#060A1C'], [0.6, '#121A36'], [1, '#232B4C']]);
      if (!animated) drawStars(g, starList(w, hy * 0.7, wide ? 120 : 70, 101));
      // 부용대 절벽
      g.fillStyle = '#0D1220'; g.beginPath(); g.moveTo(L.cliffX - h * 0.02, hy + h * 0.02);
      g.lineTo(L.cliffX, hy - h * 0.13); g.quadraticCurveTo(L.cliffX + h * 0.05, hy - h * 0.165, w * 0.9, hy - h * 0.16); g.lineTo(w, hy - h * 0.15); g.lineTo(w, hy + h * 0.02); g.closePath(); g.fill();
      g.strokeStyle = 'rgba(90,100,130,0.25)'; g.lineWidth = 2;
      for (let k = 0; k < 6; k++) { g.beginPath(); g.moveTo(L.cliffX + k * h * 0.012, hy - h * 0.12 + k * h * 0.02); g.lineTo(L.cliffX + k * h * 0.012 + h * 0.01, hy + h * 0.01); g.stroke(); }
      // 강물
      vgrad(g, 0, hy - h * 0.005, w, hy + h * 0.03, [[0, '#1A2448'], [1, '#0E1530']]);
      // 초가지붕 마을과 창 불빛
      const rnd = mulberry32(31);
      for (let i = 0; i < (wide ? 12 : 7); i++) {
        const x = (i + rnd() * 0.5) / (wide ? 12 : 7) * L.cliffX * 0.95, y = hy - h * 0.004, r = h * (0.022 + rnd() * 0.012);
        g.fillStyle = '#1A1A22'; g.fillRect(x - r * 0.8, y - r * 0.5, r * 1.6, r * 0.5);
        g.fillStyle = '#3A3226'; g.beginPath(); g.ellipse(x, y - r * 0.5, r * 1.05, r * 0.55, 0, Math.PI, 0); g.fill();
        g.fillStyle = 'rgba(255,200,120,0.85)'; g.fillRect(x - r * 0.2, y - r * 0.35, r * 0.25, r * 0.22);
      }
      // 줄불 줄
      g.strokeStyle = 'rgba(255,170,90,0.35)'; g.lineWidth = Math.max(1, h * 0.0015);
      L.ropes.forEach(([x, y]) => { g.beginPath(); g.moveTo(...L.top); g.quadraticCurveTo((L.top[0] + x) / 2, (L.top[1] + y) / 2 + h * 0.03, x, y); g.stroke(); });
      if (!animated) L.ropes.forEach(([x, y]) => { for (let k = 1; k < 12; k++) { const u = k / 12; const px = L.top[0] + (x - L.top[0]) * u, py = L.top[1] + (y - L.top[1]) * u + h * 0.03 * 4 * u * (1 - u) * 0.5; bokeh(g, px, py + h * 0.01, h * 0.008, 0.7, '255,170,80'); } });
      ground(g, w, h, hy + h * 0.03, [[0, '#2E2820'], [0.4, '#1C1812'], [1, '#090806']]);
      strokes(g, w, h, hy + h * 0.03, 77, 500, ['150,130,90', '20,15,8'], 0.003, 0.012);
      floorPool(g, pool, 'rgba(255,200,140,0.28)', 'rgba(200,150,100,0.10)');
    },
    async build(scene, layout, animated) {
      const face = mat('#D2A46A', { rough: 0.55, env: 0.6, double: true });
      const line = mat('#2A1A10', { rough: 0.5 });
      const cheek = mat('#C4704E', { rough: 0.6 });
      const post = mat('#4A3020', { rough: 0.8 });
      const SX = 0.85, SY = 1.15, SZ = 0.5, R = 0.6;
      const at = (x, y) => [x, y, SZ * R * Math.sqrt(Math.max(0, 1 - (x / (R * SX)) ** 2 - (y / (R * SY)) ** 2)) + 0.01];
      const upper = group(
        mesh(new T.SphereGeometry(R, 48, 32, 0, Math.PI, 0.04 * Math.PI, 0.6 * Math.PI), face, null, [0, -Math.PI / 2 + Math.PI / 2, 0], [SX, SY, SZ]),
        // 웃는 눈(∩)과 눈썹
        ring(0.1, 0.02, line, at(-0.2, 0.12), null, Math.PI), ring(0.1, 0.02, line, at(0.2, 0.12), null, Math.PI),
        ring(0.14, 0.018, line, at(-0.2, 0.27), null, Math.PI), ring(0.14, 0.018, line, at(0.2, 0.27), null, Math.PI),
        ball(0.09, face, at(0, -0.02), [0.8, 1.5, 1]),
        // 볼 주름과 볼연지
        ring(0.08, 0.012, line, at(-0.32, -0.08), [0, 0, 0.6], Math.PI * 0.8), ring(0.08, 0.012, line, at(0.32, -0.08), [0, 0, 2.5], Math.PI * 0.8),
        ball(0.07, cheek, at(-0.3, -0.04), [1, 0.7, 0.3]), ball(0.07, cheek, at(0.3, -0.04), [1, 0.7, 0.3]),
        // 윗입술 웃음(∪)
        ring(0.19, 0.018, line, at(0, -0.25), [0, 0, Math.PI], Math.PI),
      );
      upper.children[0].rotation.set(0, 0, 0);
      // 떨어진 턱: 웃을 때 벌어진다(경첩은 턱 위쪽)
      const jawPivot = new T.Group(); jawPivot.position.set(0, -0.36, 0.12);
      const jaw = group(
        mesh(new T.SphereGeometry(R, 48, 16, 0, Math.PI, 0.67 * Math.PI, 0.27 * Math.PI), face, null, null, [SX, SY, SZ]),
        ring(0.15, 0.018, line, [0, -0.5, 0.17], [0, 0, Math.PI], Math.PI * 0.9),
      );
      jaw.position.set(0, 0.36, -0.12); jawPivot.add(jaw);
      const mask = group(upper, jawPivot);
      mask.position.set(0, 1.95, 0.06);
      const stand = group(
        box(0.8, 0.12, 0.5, post, [0, 0.06, 0]),
        cyl(0.06, 0.07, 1.9, post, [0, 1.0, -0.05], null, 12),
        // 탈을 묶은 끈
        rod([-0.45, 2.1, 0.0], [0, 2.3, -0.05], 0.012, line), rod([0.45, 2.1, 0.0], [0, 2.3, -0.05], 0.012, line),
      );
      place(scene, group(stand, mask), layout, { fit: 3.0, yaw: 0.4 });
      if (!animated) { jawPivot.rotation.x = 0.12; return () => {}; }
      return (t) => { jawPivot.rotation.x = 0.03 + 0.17 * Math.max(0, Math.sin(t * fq(2.2))); };
    },
    fx(w, h, wide, geo) {
      const hy = geo.horizonY;
      const L = andongLayout(w, h, hy, wide);
      const stars = starList(w, hy * 0.7, wide ? 120 : 70, 101);
      const rnd = mulberry32(55);
      // 줄불: 줄에 매단 숯가루 불꽃이 떨어진다
      const sparks = [];
      L.ropes.forEach(([x, y]) => {
        for (let k = 0; k < 26; k++) {
          const u = 0.04 + rnd() * 0.92;
          sparks.push({ x: L.top[0] + (x - L.top[0]) * u, y: L.top[1] + (y - L.top[1]) * u + h * 0.03 * 4 * u * (1 - u) * 0.5, p: rnd(), n: 2 + Math.floor(rnd() * 3), d: h * (0.02 + rnd() * 0.04) });
        }
      });
      const boats = Array.from({ length: wide ? 7 : 4 }, () => ({ p: rnd(), y: hy + h * (0.004 + rnd() * 0.02) }));
      return (g, t) => {
        drawStars(g, stars, t);
        sparks.forEach((s) => {
          const u = cyc(t, s.n, s.p);
          bokeh(g, s.x, s.y + u * u * s.d, h * 0.006, (1 - u) * (0.6 + 0.4 * Math.sin(t * fq(6) + s.p * 9)), '255,170,80');
        });
        // 강물 위 달걀불이 천천히 흘러간다
        boats.forEach((b) => { const u = cyc(t, 1, b.p); bokeh(g, w * (0.95 - u * 0.9), b.y, h * 0.012, 0.8 * Math.sin(Math.PI * u), '255,190,110'); });
      };
    },
  };

  Object.assign(window.TOWNS, { seoul, busan, daegu, incheon, gwangju, daejeon, gyeongju, jeonju, gangneung, jeju, yeosu, andong });
})();
