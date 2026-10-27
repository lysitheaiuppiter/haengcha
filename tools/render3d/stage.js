/*
 * 행차 무대: 지금 테마 탈것을 올린 단상과 지역 풍경(궁궐 마당, 울산 밤바다와 고래, 횡성 들판과 한우).
 * 도감 지역 12곳의 장면은 towns.js에 있고(window.TOWNS), 여기 도우미(window.Stage)를 쓴다.
 * 한 코드로 두 가지를 그린다(tools/render3d/render.mjs가 헤드리스 크롬에서 부른다).
 *  - 정지 그림(window.renderLayer): 탈것 레이어(투명)와 배경 레이어, 도감 아이콘
 *  - 움직이는 배경(window.Anim): 고래가 뛰어오르고, 등대 불빛이 돌고, 반딧불·초롱·별이 일렁이는 루프 영상의 프레임.
 *    모든 움직임의 주기를 루프 길이(LOOP_SEC)에 맞춰 끝 프레임이 첫 프레임으로 끊김 없이 이어진다.
 * 앱은 배경 영상 위에 탈것 레이어를 겹친다. Pleos 에뮬레이터의 WebView는 캔버스·WebGL을 화면에 합성하지 못해
 * 앱 안에서 three.js를 실시간으로 돌리지 않고 미리 렌더한 영상을 쓴다.
 * 조명·단상·금빛 고리는 소개 영상의 테마 해금 장면(stage3d.js, s09.js)과 같은 구성이다.
 * 내비게이션처럼 보이지 않게 길·소실점·지도 요소는 넣지 않는다. 하늘·먼 풍경은 2D로, 탈것·동물·소품은 three.js로 그린다.
 */
(function () {
  const T = window.THREE;
  const MODEL_BASE = window.MODEL_BASE || 'models/';

  const YAW = 2.25; // 탈것 앞(glTF -Z)이 화면 왼쪽 앞을 보게
  const REAR_YAW = 0, REAR_Z = -5.5; // 주행 화면: 탈것 앞이 화면 안쪽을 보게(뒷모습), 단상보다 앞에
  const TOP = 0.35; // 단상 윗면 높이
  const SHORE_Z = -6; // 울산: 이보다 먼 곳은 바다
  // landscape는 왼쪽에 카드가 오므로 탈것을 오른쪽으로, portrait는 위에 카드가 오므로 아래로 민다
  const FRAME = {
    landscape: { fov: 30, cam: [0, 3.0, 12.4], shift: [0.15, 0.06] },
    portrait: { fov: 30, cam: [0, 3.6, 14.3], shift: [0, 0.2] },
  };
  // 단골 등급별 고래 빛깔: 노말, 동, 은, 금, 플래티넘
  const WHALE = [
    { skin: '#1E5A9C', belly: '#DCE3EA', groove: '#BCC9D8', metal: 0.0, rough: 0.35 },
    { skin: '#A0582A', belly: '#E3B07C', groove: '#D29A66', metal: 0.85, rough: 0.3 },
    { skin: '#B9C2CC', belly: '#EEF2F5', groove: '#CDD5DE', metal: 0.95, rough: 0.22 },
    { skin: '#D99A2B', belly: '#FFE2A2', groove: '#F6CF7E', metal: 1.0, rough: 0.22 },
    { skin: '#DCE6F0', belly: '#FFFFFF', groove: '#EAF1F8', metal: 1.0, rough: 0.12, glow: '#7FD6FF' },
  ];
  // 단골 등급별 한우 메달: 3등급(없음), 2등급, 1등급, 1+, 1++
  const MEDAL = [null, '#B4703A', '#C9D1DA', '#F2B84B', '#F2B84B'];
  // 루프 길이(초). 고래가 한 번 뛰어올랐다 잠기는 주기이기도 하다. AIR는 그중 물 밖에 있는 비율
  const LOOP_SEC = 11, BREACH_SEC = LOOP_SEC, AIR = 0.5;
  // 잠겨 있는 동안 고래가 숨을 쉬며 물기둥을 뿜는 때(주기 안 비율)
  const SPOUT = 0.74;
  /** 각속도(rad/s)를 루프 안에서 정수 번 도는 값으로 맞춘다(최소 한 번). */
  const fq = (f) => Math.max(1, Math.round((f * LOOP_SEC) / (2 * Math.PI))) * (2 * Math.PI) / LOOP_SEC;
  // 물보라 입자도 매번 같은 영상이 나오게 정해진 난수를 쓴다
  let rand = mulberry32(99);
  // 정지 그림에서 고래가 있는 순간(떠오르는 중)
  const STILL_U = 0.38;

  function mulberry32(seed) {
    return function () {
      seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
      let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  function radialTexture(stops) {
    const c = document.createElement('canvas'); c.width = c.height = 256;
    const g = c.getContext('2d');
    const r = g.createRadialGradient(128, 128, 0, 128, 128, 128);
    stops.forEach(([t, col]) => r.addColorStop(t, col));
    g.fillStyle = r; g.fillRect(0, 0, 256, 256);
    return new T.CanvasTexture(c);
  }

  const modelCache = {};
  function loadModel(name) {
    if (!modelCache[name]) {
      modelCache[name] = new Promise((res, rej) => new T.GLTFLoader().load(MODEL_BASE + name + '.glb', (g) => res(g.scene), undefined, rej));
    }
    // 같은 모델을 여러 번 쓰므로 재질까지 복제해 돌려준다(등급별로 색을 바꾼다)
    return modelCache[name].then((s) => {
      const c = s.clone(true);
      c.traverse((o) => { if (o.isMesh) o.material = Array.isArray(o.material) ? o.material.map((m) => m.clone()) : o.material.clone(); });
      return c;
    });
  }

  function makeRenderer(canvas, w, h, shadowSize) {
    const r = new T.WebGLRenderer({ canvas, antialias: true, alpha: true, preserveDrawingBuffer: true });
    r.setPixelRatio(1); r.setSize(w, h, false); r.setClearColor(0x000000, 0);
    r.outputEncoding = T.sRGBEncoding; r.toneMapping = T.ACESFilmicToneMapping; r.toneMappingExposure = 1.05;
    r.shadowMap.enabled = true; r.shadowMap.type = T.PCFSoftShadowMap;
    r.userData = { shadowSize };
    return r;
  }

  function makeScene(r) {
    const scene = new T.Scene();
    const pm = new T.PMREMGenerator(r);
    scene.environment = pm.fromScene(new T.RoomEnvironment(), 0.04).texture;
    pm.dispose();
    return scene;
  }

  function makeCamera(w, h, layout) {
    const F = FRAME[layout];
    const cam = new T.PerspectiveCamera(F.fov, w / h, 0.1, 400);
    cam.position.set(...F.cam); cam.lookAt(0, 1.0, 0);
    // 원근은 그대로 두고 화면 안 위치만 옮긴다
    cam.setViewOffset(w, h, -F.shift[0] * w, -F.shift[1] * h, w, h);
    cam.updateMatrixWorld();
    const toScreen = (x, y, z) => { const v = new T.Vector3(x, y, z).project(cam); return [(v.x + 1) / 2 * w, (1 - v.y) / 2 * h]; };
    const [px, py] = toScreen(0, 0, 0);
    const geo = {
      horizonY: toScreen(0, 0, -1e4)[1],
      shoreY: toScreen(0, 0, SHORE_Z)[1],
      pool: { x: px, y: py, rx: Math.abs(toScreen(2.4, 0, 0)[0] - px), ry: Math.abs(toScreen(0, 0, 2.4)[1] - py) },
      toScreen,
    };
    return { cam, geo };
  }

  function addLights(scene, shadowSize, key = 0xffd8a8) {
    scene.add(new T.HemisphereLight(0x9fd4cf, 0x08201f, 0.35));
    const spot = new T.SpotLight(key, 2.4, 60, 0.6, 0.6, 1.0);
    spot.position.set(5, 9, 7); spot.castShadow = true; spot.shadow.mapSize.set(shadowSize, shadowSize); spot.shadow.bias = -0.0004;
    spot.target.position.set(0, 0.6, -2);
    scene.add(spot); scene.add(spot.target);
    const rim = new T.DirectionalLight(0x86b4ff, 1.3); rim.position.set(-5, 4, -6); scene.add(rim);
    const fill = new T.DirectionalLight(0xffffff, 0.3); fill.position.set(-4, 3, 6); scene.add(fill);
    return spot;
  }

  function shadowGround(scene, opacity = 0.45) {
    const ground = new T.Mesh(new T.PlaneGeometry(120, 120), new T.ShadowMaterial({ opacity }));
    ground.rotation.x = -Math.PI / 2; ground.receiveShadow = true; scene.add(ground);
  }

  function sprite(map, opacity, renderOrder = 2) {
    const sp = new T.Sprite(new T.SpriteMaterial({ map, transparent: true, depthWrite: false, opacity, toneMapped: false }));
    sp.renderOrder = renderOrder;
    return sp;
  }

  function bokeh(g, x, y, r, a, rgb) {
    const b = g.createRadialGradient(x, y, 0, x, y, r);
    b.addColorStop(0, `rgba(${rgb},${a})`); b.addColorStop(0.45, `rgba(${rgb},${a * 0.45})`); b.addColorStop(1, `rgba(${rgb},0)`);
    g.fillStyle = b; g.beginPath(); g.arc(x, y, r, 0, Math.PI * 2); g.fill();
  }

  function starList(w, maxY, n, seed) {
    const rnd = mulberry32(seed);
    return Array.from({ length: n }, () => ({ x: rnd() * w, y: rnd() * maxY, r: 0.6 + rnd() * 1.4, a: 0.25 + rnd() * 0.55, p: rnd() * 6.3, f: 0.6 + rnd() * 1.6 }));
  }

  function drawStars(g, list, t) {
    list.forEach((s) => {
      const a = t == null ? s.a : s.a * (0.45 + 0.55 * Math.abs(Math.sin(t * fq(s.f) + s.p)));
      g.fillStyle = `rgba(235,240,255,${a})`;
      g.beginPath(); g.arc(s.x, s.y, s.r, 0, Math.PI * 2); g.fill();
    });
  }

  function floorPool(g, pool, inner, outer) {
    g.save();
    g.translate(pool.x, pool.y); g.scale(1, pool.ry / pool.rx);
    const p = g.createRadialGradient(0, 0, 0, 0, 0, pool.rx * 1.9);
    p.addColorStop(0, inner); p.addColorStop(0.5, outer); p.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = p; g.beginPath(); g.arc(0, 0, pool.rx * 1.9, 0, Math.PI * 2); g.fill();
    g.restore();
  }

  // 능선 실루엣. 사인파 몇 개를 겹쳐 산 모양을 만든다
  function ridge(g, w, baseY, amp, seed, color) {
    const rnd = mulberry32(seed);
    const waves = [0, 1, 2, 3].map((i) => ({ f: (1.5 + rnd() * 2.5) * (i + 1), p: rnd() * 6.3, a: 1 / (i + 1.3) }));
    g.fillStyle = color; g.beginPath(); g.moveTo(0, baseY + 2);
    for (let x = 0; x <= w; x += 4) {
      const u = x / w;
      const v = waves.reduce((s, k) => s + k.a * Math.sin(u * k.f * Math.PI + k.p), 0);
      g.lineTo(x, baseY - amp * (0.55 + 0.45 * v));
    }
    g.lineTo(w, baseY + 2); g.closePath(); g.fill();
  }

  // ---------------- 단상과 탈것 ----------------

  function buildStage(scene) {
    const pl = new T.PointLight(0xffc477, 2.4, 9, 2); pl.position.set(0, 3.4, 2.0); scene.add(pl);
    const stone = (c) => new T.MeshStandardMaterial({ color: c, roughness: 0.5, metalness: 0.3, envMapIntensity: 0.25 });
    const step = new T.Mesh(new T.CylinderGeometry(2.35, 2.4, 0.1, 96), stone('#081C1B'));
    step.position.y = 0.05; step.receiveShadow = true; scene.add(step);
    const ped = new T.Mesh(new T.CylinderGeometry(2.0, 2.05, TOP - 0.1, 96), stone('#0D2927'));
    ped.position.y = 0.1 + (TOP - 0.1) / 2; ped.receiveShadow = true; ped.castShadow = true; scene.add(ped);
    const ring = new T.Mesh(new T.TorusGeometry(2.0, 0.035, 12, 160), new T.MeshStandardMaterial({ color: '#000000', emissive: new T.Color('#F2B84B'), emissiveIntensity: 1.8 }));
    ring.rotation.x = Math.PI / 2; ring.position.y = TOP; scene.add(ring);
  }

  /** [yaw]는 탈것 앞이 보는 방향(정차 화면은 왼쪽 앞, 주행 화면은 0이면 화면 안쪽), [base]는 바닥 높이(단상 윗면 또는 땅). */
  async function buildVehicle(scene, name, yaw = YAW, base = TOP) {
    const model = await loadModel(name);
    model.traverse((o) => { if (o.isMesh) { o.castShadow = true; o.receiveShadow = true; if (o.material) o.material.envMapIntensity = 0.55; } });
    // 가장 긴 변을 단상 지름(4.0)의 4/5쯤에 맞춘다. 키가 큰 어가 행렬도 화면에 들어오게 높이도 본다
    const box = new T.Box3().setFromObject(model);
    const size = box.getSize(new T.Vector3()), center = box.getCenter(new T.Vector3());
    const scale = 3.3 / Math.max(size.x, size.z, size.y * 1.15);
    model.scale.setScalar(scale);
    model.position.set(-center.x * scale, base - box.min.y * scale, -center.z * scale);
    // 바퀴·발 밑에 닿는 그림자(접지감)
    const contact = new T.Mesh(
      new T.PlaneGeometry(size.x * scale * 1.35, size.z * scale * 1.2),
      new T.MeshBasicMaterial({ map: radialTexture([[0, 'rgba(0,0,0,0.4)'], [1, 'rgba(0,0,0,0)']]), transparent: true, depthWrite: false, toneMapped: false, polygonOffset: true, polygonOffsetFactor: -2 }),
    );
    contact.rotation.x = -Math.PI / 2; contact.position.y = base + 0.002;
    const pivot = new T.Group(); pivot.add(model); pivot.add(contact); pivot.rotation.y = yaw; scene.add(pivot);
    // 바퀴·발·다리가 단상에 닿는 곳마다 작고 진한 그림자를 둔다(떠 보이지 않게). 단상과 함께 돌도록 pivot에 붙인다
    pivot.updateMatrixWorld(true);
    const blob = radialTexture([[0, 'rgba(0,0,0,0.75)'], [0.5, 'rgba(0,0,0,0.35)'], [1, 'rgba(0,0,0,0)']]);
    const inv = new T.Matrix4().copy(pivot.matrixWorld).invert();
    model.traverse((o) => {
      if (!o.isMesh) return;
      const pos = o.geometry.attributes.position, v = new T.Vector3();
      let x0 = Infinity, x1 = -Infinity, z0 = Infinity, z1 = -Infinity, n = 0;
      for (let i = 0; i < pos.count; i++) {
        v.fromBufferAttribute(pos, i).applyMatrix4(o.matrixWorld);
        if (v.y > base + 0.06) continue;
        v.applyMatrix4(inv);
        x0 = Math.min(x0, v.x); x1 = Math.max(x1, v.x); z0 = Math.min(z0, v.z); z1 = Math.max(z1, v.z); n++;
      }
      if (!n) return;
      const pad = 0.28;
      const m = new T.Mesh(new T.PlaneGeometry(x1 - x0 + pad, z1 - z0 + pad), new T.MeshBasicMaterial({ map: blob, transparent: true, depthWrite: false, toneMapped: false, polygonOffset: true, polygonOffsetFactor: -3 }));
      m.rotation.x = -Math.PI / 2; m.position.set((x0 + x1) / 2, base + 0.003, (z0 + z1) / 2); pivot.add(m);
    });
    return pivot;
  }

  // ---------------- 궁궐 마당(지역 밖) ----------------

  function drawHall(g, cx, baseY, W, H) {
    const P = (u, v) => [cx + u * W, baseY - v * H];
    g.beginPath();
    g.moveTo(...P(-0.40, 0)); g.lineTo(...P(0.40, 0)); g.lineTo(...P(0.40, 0.06)); g.lineTo(...P(0.33, 0.06));
    g.lineTo(...P(0.33, 0.34));
    g.quadraticCurveTo(...P(0.44, 0.33), ...P(0.52, 0.44)); // 처마 끝이 위로 들린다
    g.quadraticCurveTo(...P(0.36, 0.52), ...P(0.27, 0.80));
    g.lineTo(...P(0.25, 0.93)); g.lineTo(...P(0.27, 0.98));
    g.lineTo(...P(-0.27, 0.98)); g.lineTo(...P(-0.25, 0.93)); g.lineTo(...P(-0.27, 0.80));
    g.quadraticCurveTo(...P(-0.36, 0.52), ...P(-0.52, 0.44));
    g.quadraticCurveTo(...P(-0.44, 0.33), ...P(-0.33, 0.34));
    g.lineTo(...P(-0.33, 0.06)); g.lineTo(...P(-0.40, 0.06)); g.closePath();
    g.fill();
  }

  function palaceLanterns(w, h, wide, horizonY) {
    const rnd = mulberry32(wide ? 11 : 23);
    const n = wide ? 15 : 8, list = [];
    for (let i = 0; i < n; i++) {
      const x = ((i + 0.5) / n) * w + (rnd() - 0.5) * w * 0.02;
      list.push({ x, y: horizonY - h * 0.028, r: h * (0.012 + rnd() * 0.006), a: 0.85, rgb: rnd() > 0.25 ? '255,196,120' : '255,120,90', p: rnd() * 6.3 });
    }
    for (let i = 0; i < (wide ? 9 : 6); i++) {
      list.push({ x: rnd() * w, y: horizonY * (0.1 + rnd() * 0.55), r: h * (0.02 + rnd() * 0.025), a: 0.16 + rnd() * 0.16, rgb: rnd() > 0.3 ? '255,196,120' : '255,120,90', p: rnd() * 6.3, drift: true });
    }
    return list;
  }

  function paintPalace(g, w, h, wide, geo, animated) {
    const { horizonY, pool } = geo;
    const sky = g.createLinearGradient(0, 0, 0, horizonY);
    sky.addColorStop(0, '#08201F'); sky.addColorStop(1, '#14423E');
    g.fillStyle = sky; g.fillRect(0, 0, w, horizonY + 1);
    const floor = g.createLinearGradient(0, horizonY, 0, h);
    floor.addColorStop(0, '#0B2E2C'); floor.addColorStop(1, '#03100F');
    g.fillStyle = floor; g.fillRect(0, horizonY, w, h - horizonY);
    const glow = g.createRadialGradient(pool.x, horizonY, 0, pool.x, horizonY, w * 0.5);
    glow.addColorStop(0, 'rgba(242,184,75,0.22)'); glow.addColorStop(1, 'rgba(242,184,75,0)');
    g.fillStyle = glow; g.fillRect(0, 0, w, h);

    const unit = h;
    g.save(); g.filter = 'blur(1.2px)'; g.fillStyle = '#071B1B';
    const halls = wide ? [[0.22, 0.20, 0.12], [0.60, 0.30, 0.17], [0.92, 0.18, 0.11]] : [[0.20, 0.26, 0.14], [0.75, 0.34, 0.18]];
    halls.forEach(([x, wr, hr]) => drawHall(g, w * x, horizonY - unit * 0.03, unit * wr, unit * hr));
    g.fillRect(0, horizonY - unit * 0.03, w, unit * 0.03);
    g.fillRect(0, horizonY - unit * 0.0435, w, unit * 0.012); // 담장 기와
    g.restore();
    // 초롱 불빛: 움직이는 배경에서는 일렁이게 따로 그린다
    if (!animated) palaceLanterns(w, h, wide, horizonY).forEach((b) => bokeh(g, b.x, b.y, b.r, b.a, b.rgb));
    floorPool(g, pool, 'rgba(255,200,130,0.30)', 'rgba(120,200,190,0.10)');
  }

  function palaceFx(w, h, wide, geo) {
    const list = palaceLanterns(w, h, wide, geo.horizonY);
    return (g, t) => list.forEach((b) => {
      const flicker = 0.78 + 0.22 * Math.sin(t * fq(3.1) + b.p) * Math.sin(t * fq(1.7) + b.p * 2);
      const dx = b.drift ? Math.sin(t * fq(0.15) + b.p) * h * 0.02 : 0, dy = b.drift ? Math.cos(t * fq(0.11) + b.p) * h * 0.012 : 0;
      bokeh(g, b.x + dx, b.y + dy, b.r, b.a * flicker, b.rgb);
    });
  }

  // ---------------- 울산: 밤바다, 간절곶 등대, 뛰어오르는 고래 ----------------

  function seaLayout(w, h, wide, horizonY) {
    const lx = w * (wide ? 0.9 : 0.88), lb = horizonY - h * 0.045, lh = h * 0.07;
    return { mx: w * (wide ? 0.84 : 0.78), my: horizonY * (wide ? 0.32 : 0.4), mr: h * 0.032, lx, lb, lh, ly: lb - lh - h * 0.012 };
  }

  function paintSea(g, w, h, wide, geo, animated) {
    const { horizonY, shoreY, pool } = geo;
    const L = seaLayout(w, h, wide, horizonY);
    const sky = g.createLinearGradient(0, 0, 0, horizonY);
    sky.addColorStop(0, '#040C1C'); sky.addColorStop(0.7, '#0B2340'); sky.addColorStop(1, '#173A5A');
    g.fillStyle = sky; g.fillRect(0, 0, w, horizonY + 1);
    // 별: 움직이는 배경에서는 반짝이게 따로 그린다
    if (!animated) drawStars(g, starList(w, horizonY * 0.8, wide ? 160 : 90, 5));
    // 달과 달무리
    bokeh(g, L.mx, L.my, L.mr * 7, 0.22, '190,215,255');
    g.fillStyle = '#F4F1E4'; g.beginPath(); g.arc(L.mx, L.my, L.mr, 0, Math.PI * 2); g.fill();
    g.fillStyle = 'rgba(200,196,180,0.35)';
    [[-0.3, -0.2, 0.22], [0.25, 0.15, 0.16], [0.05, 0.4, 0.12]].forEach(([dx, dy, rr]) => { g.beginPath(); g.arc(L.mx + dx * L.mr, L.my + dy * L.mr, rr * L.mr, 0, Math.PI * 2); g.fill(); });

    // 먼 해안선: 왼쪽 낮은 산, 오른쪽 곶과 등대
    ridge(g, w * 0.42, horizonY, h * 0.035, 3, '#081A2C');
    g.save(); g.translate(w * 0.74, 0); ridge(g, w * 0.26, horizonY, h * 0.05, 9, '#081A2C'); g.restore();
    g.fillStyle = '#C9D2DA'; g.beginPath();
    g.moveTo(L.lx - h * 0.009, L.lb); g.lineTo(L.lx + h * 0.009, L.lb); g.lineTo(L.lx + h * 0.006, L.lb - L.lh); g.lineTo(L.lx - h * 0.006, L.lb - L.lh); g.closePath(); g.fill();
    g.fillStyle = '#081A2C'; g.fillRect(L.lx - h * 0.009, L.lb - L.lh - h * 0.004, h * 0.018, h * 0.004);
    // 등대 빛줄기: 움직이는 배경에서는 돌아가게 따로 그린다
    if (!animated) {
      const beam = g.createLinearGradient(L.lx, L.ly, L.lx - w * 0.35, L.ly + h * 0.02);
      beam.addColorStop(0, 'rgba(255,236,170,0.35)'); beam.addColorStop(1, 'rgba(255,236,170,0)');
      g.fillStyle = beam; g.beginPath(); g.moveTo(L.lx, L.ly - h * 0.004); g.lineTo(L.lx - w * 0.35, L.ly - h * 0.04); g.lineTo(L.lx - w * 0.35, L.ly + h * 0.05); g.lineTo(L.lx, L.ly + h * 0.004); g.closePath(); g.fill();
      bokeh(g, L.lx, L.ly, h * 0.03, 0.95, '255,236,170');
    }

    // 바다
    const sea = g.createLinearGradient(0, horizonY, 0, shoreY);
    sea.addColorStop(0, '#0C2740'); sea.addColorStop(1, '#0E3550');
    g.fillStyle = sea; g.fillRect(0, horizonY, w, shoreY - horizonY);
    // 달빛 물비늘: 달 아래로 짧은 가로 물결이 흩어진다
    const rnd = mulberry32(17);
    for (let i = 0; i < 260; i++) {
      const t = rnd();
      const y = horizonY + 2 + (shoreY - horizonY - 4) * t * t;
      const spread = w * (0.015 + 0.09 * t);
      const x = L.mx + (rnd() - 0.5) * 2 * spread;
      const len = h * (0.006 + 0.02 * t) * (0.5 + rnd());
      g.fillStyle = `rgba(230,236,255,${(0.15 + 0.5 * rnd()) * (1 - 0.5 * t)})`;
      g.fillRect(x - len / 2, y, len, Math.max(1, h * 0.0016 * (1 + t)));
    }
    for (let i = 0; i < 120; i++) {
      const t = rnd(); const y = horizonY + 3 + (shoreY - horizonY - 6) * t;
      g.fillStyle = `rgba(150,190,230,${0.06 + 0.12 * rnd()})`;
      g.fillRect(rnd() * w, y, h * (0.01 + 0.03 * t), 1.5);
    }
    // 바닷가(단상이 선 곳)와 물가 거품
    const shore = g.createLinearGradient(0, shoreY, 0, h);
    shore.addColorStop(0, '#12303A'); shore.addColorStop(1, '#040E14');
    g.fillStyle = shore; g.fillRect(0, shoreY, w, h - shoreY);
    g.strokeStyle = 'rgba(220,235,255,0.35)'; g.lineWidth = h * 0.004;
    g.beginPath();
    for (let x = 0; x <= w; x += 6) {
      const y = shoreY + Math.sin(x / w * 23) * h * 0.003 + Math.sin(x / w * 61 + 1) * h * 0.0015;
      if (x === 0) g.moveTo(x, y); else g.lineTo(x, y);
    }
    g.stroke();
    floorPool(g, pool, 'rgba(255,214,160,0.26)', 'rgba(110,170,220,0.10)');
  }

  function seaFx(w, h, wide, geo) {
    const L = seaLayout(w, h, wide, geo.horizonY);
    const stars = starList(w, geo.horizonY * 0.8, wide ? 160 : 90, 5);
    // 달빛 물비늘 위에서 반짝이는 점
    const rnd = mulberry32(57);
    const glints = Array.from({ length: 70 }, () => {
      const t = rnd();
      return { x: L.mx + (rnd() - 0.5) * 2 * w * (0.015 + 0.09 * t), y: geo.horizonY + 2 + (geo.shoreY - geo.horizonY - 4) * t * t, len: h * (0.008 + 0.022 * t), p: rnd() * 6.3, f: 1.5 + rnd() * 3, t };
    });
    return (g, t) => {
      drawStars(g, stars, t);
      glints.forEach((s) => {
        const a = Math.max(0, Math.sin(t * fq(s.f) + s.p)) * 0.7 * (1 - 0.5 * s.t);
        if (a < 0.02) return;
        g.fillStyle = `rgba(245,248,255,${a})`;
        g.fillRect(s.x - s.len / 2, s.y, s.len, Math.max(1, h * 0.0018));
      });
      // 등대 빛줄기가 돈다. 바다 쪽(왼쪽)을 비출 때 길고 밝게, 화면 쪽을 볼 때 등불이 번쩍인다
      const a = t * fq(0.9);
      const reach = Math.cos(a), face = Math.sin(a);
      if (reach < 0) {
        const len = w * 0.42 * -reach, alpha = 0.4 * -reach;
        const beam = g.createLinearGradient(L.lx, L.ly, L.lx - len, L.ly);
        beam.addColorStop(0, `rgba(255,236,170,${alpha})`); beam.addColorStop(1, 'rgba(255,236,170,0)');
        g.fillStyle = beam; g.beginPath();
        g.moveTo(L.lx, L.ly - h * 0.004); g.lineTo(L.lx - len, L.ly - h * 0.045 * -reach); g.lineTo(L.lx - len, L.ly + h * 0.05 * -reach); g.lineTo(L.lx, L.ly + h * 0.004);
        g.closePath(); g.fill();
      }
      bokeh(g, L.lx, L.ly, h * (0.03 + 0.03 * Math.max(0, face)), 0.7 + 0.3 * Math.max(0, face), '255,236,170');
    };
  }

  async function buildSea(scene, layout, grade, animated) {
    const wide = layout === 'landscape';
    // 수면 아래를 가리는 투명 판(깊이만 쓴다). 물 밖으로 나온 고래 몸만 보인다
    const occluder = new T.Mesh(new T.PlaneGeometry(400, 400), new T.MeshBasicMaterial({ colorWrite: false }));
    occluder.rotation.x = -Math.PI / 2; occluder.renderOrder = -1; scene.add(occluder);

    const c = WHALE[grade];
    const whale = await loadModel('whale');
    whale.traverse((o) => {
      if (!o.isMesh) return;
      const mats = Array.isArray(o.material) ? o.material : [o.material];
      mats.forEach((m) => {
        const key = { WhaleSkin: 'skin', WhaleBelly: 'belly', WhaleGroove: 'groove' }[m.name];
        if (!key) return;
        m.color = new T.Color(c[key]); m.metalness = c.metal; m.roughness = c.rough; m.envMapIntensity = c.metal > 0 ? 1.4 : 0.6;
        if (c.glow && key === 'skin') { m.emissive = new T.Color(c.glow); m.emissiveIntensity = 0.12; }
      });
    });
    // 진행 방향(머리 쪽): 오른쪽, 조금 화면 쪽. 몸을 조금 굴려 등과 꼬리지느러미가 보이게
    const roll = new T.Group(); roll.add(whale); roll.rotation.z = 0.35;
    const pitch = new T.Group(); pitch.add(roll);
    const heading = new T.Group(); heading.add(pitch); heading.rotation.y = -2.0;
    const s = wide ? 1.45 : 1.3;
    heading.scale.setScalar(s);
    scene.add(heading);
    const dir = new T.Vector3(Math.sin(2.0), 0, -Math.cos(2.0)); // 머리(glTF -Z)를 heading만큼 돌린 방향
    // 포물선: u=0에서 물속을 떠나 u=1에 다시 잠긴다. 정지 그림의 순간(STILL_U)이 예전 자리에 오게 맞춘다
    const at = new T.Vector3(...(wide ? [-4.5, 0, -15] : [-2.0, 0, -17]));
    const X = 7 * s, D = 1.6 * s, APEX = 1.9 * s;
    const start = at.clone().addScaledVector(dir, -STILL_U * X);
    const place = (u) => {
      const y = -D + (D + APEX) * 4 * u * (1 - u);
      heading.position.copy(start).addScaledVector(dir, u * X); heading.position.y = y;
      // 너무 곧추서면 꼬리 쪽만 보여 원뿔처럼 보이므로 45도 안에서만 기울인다
      pitch.rotation.x = Math.max(-0.8, Math.min(0.8, Math.atan2((D + APEX) * 4 * (1 - 2 * u), X)));
    };
    place(STILL_U);

    const spray = radialTexture([[0, 'rgba(255,255,255,0.95)'], [0.4, 'rgba(225,240,255,0.5)'], [1, 'rgba(225,240,255,0)']]);
    const foamTex = radialTexture([[0, 'rgba(255,255,255,0)'], [0.3, 'rgba(235,245,255,0.75)'], [0.55, 'rgba(235,245,255,0.25)'], [1, 'rgba(235,245,255,0)']]);
    const rnd = mulberry32(31);
    const sparkleTex = c.glow ? radialTexture([[0, 'rgba(225,245,255,1)'], [0.3, 'rgba(160,225,255,0.55)'], [1, 'rgba(160,225,255,0)']]) : null;

    if (!animated) {
      // 정지 그림: 꼬리가 빠져나온 자리의 물보라와 날리는 물방울
      const splash = [at.x - 1.9 * s, at.z + 0.5 * s];
      const add = (x, y, z, sc, op) => { const sp = sprite(spray, op); sp.position.set(x, y, z); sp.scale.set(sc, sc, 1); scene.add(sp); };
      for (let i = 0; i < 26; i++) {
        const a = rnd() * Math.PI * 2, rr = (0.3 + rnd() * 1.1) * s;
        add(splash[0] + Math.cos(a) * rr, rnd() * 0.45 * s, splash[1] + Math.sin(a) * rr * 0.6, (0.4 + rnd() * 0.7) * s, 0.35 + rnd() * 0.45);
      }
      for (let i = 0; i < 80; i++) {
        const t = rnd();
        const x = splash[0] + t * 1.6 * s + (rnd() - 0.5) * 1.2 * s;
        const y = (0.2 + t * 1.6 + rnd() * 0.8) * s * (1 - 0.3 * t);
        add(x, y, splash[1] + (rnd() - 0.5) * 1.0 * s, (0.035 + rnd() * 0.06) * s, 0.55 + rnd() * 0.45);
      }
      const foam = new T.Mesh(new T.PlaneGeometry(4.5 * s, 4.5 * s), new T.MeshBasicMaterial({ map: foamTex, transparent: true, depthWrite: false, toneMapped: false }));
      foam.rotation.x = -Math.PI / 2; foam.position.set(splash[0], 0.02, splash[1]); foam.renderOrder = 1; scene.add(foam);
      if (sparkleTex) {
        for (let i = 0; i < 28; i++) {
          const sp = sprite(sparkleTex, 0.6 + rnd() * 0.4, 3);
          const a = rnd() * Math.PI * 2, rr = (1.2 + rnd() * 1.6) * s;
          sp.position.set(at.x + Math.cos(a) * rr * 1.3, 1.75 * s + Math.sin(a) * rr * 0.7, at.z + 0.8);
          const sc = (0.1 + rnd() * 0.22) * s; sp.scale.set(sc, sc, 1); scene.add(sp);
        }
      }
    }
    // 바닷가 바위(단상 둘레는 비워 둔다)
    const rock = new T.MeshStandardMaterial({ color: '#05090B', roughness: 0.85, metalness: 0.0, envMapIntensity: 0.08, flatShading: true });
    const rocks = wide
      ? [[-4.4, -5.3, 0.6], [-3.6, -5.6, 0.32], [4.3, -5.0, 0.7], [5.1, -4.6, 0.4]]
      : [[-3.4, -5.5, 0.5], [3.3, -5.2, 0.6], [3.9, -4.9, 0.3]];
    rocks.forEach(([x, z, sz], i) => {
      const m = new T.Mesh(new T.DodecahedronGeometry(1, 0), rock);
      m.scale.set(sz * 1.3, sz * 0.7, sz); m.rotation.set(i * 0.7, i * 1.3, i * 0.4); m.position.set(x, sz * 0.25, z);
      m.castShadow = true; m.receiveShadow = true; scene.add(m);
    });
    const moon = new T.DirectionalLight(0xcfe0ff, 1.1); moon.position.set(8, 10, -20); scene.add(moon);
    if (!animated) return () => {};

    // 움직이는 배경: 물보라 입자(중력으로 떨어진다)와 퍼지는 거품 고리
    const drops = Array.from({ length: 140 }, () => { const sp = sprite(spray, 0); scene.add(sp); return { sp, life: 0, v: new T.Vector3() }; });
    const foams = [0, 1].map(() => {
      const m = new T.Mesh(new T.PlaneGeometry(1, 1), new T.MeshBasicMaterial({ map: foamTex, transparent: true, depthWrite: false, toneMapped: false, opacity: 0 }));
      m.rotation.x = -Math.PI / 2; m.renderOrder = 1; scene.add(m); return { m, life: 0 };
    });
    const sparkles = sparkleTex ? Array.from({ length: 20 }, () => { const sp = sprite(sparkleTex, 0, 3); scene.add(sp); return { sp, p: rnd() * 6.3, a: rnd() * 6.3, rr: (1.0 + rnd() * 1.4) * s }; }) : [];
    const burst = (u, n) => {
      const p = start.clone().addScaledVector(dir, u * X);
      let k = 0;
      for (const d of drops) {
        if (d.life > 0) continue;
        // 셋 중 하나는 크고 느린 물안개, 나머지는 튀는 물방울
        const mist = k % 3 === 0;
        d.life = mist ? 0.8 + rand() * 0.5 : 0.9 + rand() * 0.8;
        d.sp.position.set(p.x + (rand() - 0.5) * 1.4 * s, 0.05, p.z + (rand() - 0.5) * 0.9 * s);
        d.v.set((rand() - 0.5) * (mist ? 1.2 : 3.4) * s, (mist ? 1.2 + rand() * 1.5 : 3 + rand() * 4) * s, (rand() - 0.5) * 1.5 * s);
        d.sz = (mist ? 0.5 + rand() * 0.7 : 0.08 + rand() * 0.25) * s;
        if (++k >= n) break;
      }
      const f = foams.find((x) => x.life <= 0) || foams[0];
      f.life = 1.6; f.m.position.set(p.x, 0.02, p.z);
    };
    // 숨구멍 물기둥: 다음에 떠오를 자리에서 곧게 솟았다가 흩어진다
    const spout = () => {
      const p = start.clone().addScaledVector(dir, -0.12 * X);
      let k = 0;
      for (const d of drops) {
        if (d.life > 0) continue;
        d.life = 1.0 + rand() * 0.7;
        d.sp.position.set(p.x + (rand() - 0.5) * 0.25 * s, 0.1, p.z + (rand() - 0.5) * 0.2 * s);
        d.v.set((rand() - 0.5) * 1.1 * s, (5.5 + rand() * 3.2) * s, (rand() - 0.5) * 0.6 * s);
        d.sz = (0.1 + rand() * 0.3) * s;
        if (++k >= 50) break;
      }
      const f = foams.find((x) => x.life <= 0) || foams[0];
      f.life = 1.6; f.m.position.set(p.x, 0.02, p.z);
    };
    let last = null, prevU = -1, prevPh = -1;
    return (t) => {
      const dt = last == null ? 0 : Math.min(0.1, t - last); last = t;
      const ph = (t / BREACH_SEC + STILL_U * AIR) % 1; // 처음 화면은 정지 그림과 같은 순간에서 시작한다
      const airborne = ph < AIR;
      const u = airborne ? ph / AIR : 1.2; // 물속에 있을 때는 수면 아래 멀리
      place(Math.min(u, 1.05));
      heading.visible = airborne;
      // 물을 떠날 때와 다시 잠길 때 물보라
      if (airborne && prevU >= 0 && prevU < 0.08 && u >= 0.08) burst(0.08, 60);
      if (airborne && prevU >= 0 && prevU < 0.9 && u >= 0.9) burst(0.92, 75);
      prevU = airborne ? u : -1;
      if (prevPh >= 0 && prevPh < SPOUT && ph >= SPOUT) spout();
      prevPh = ph;
      drops.forEach((d) => {
        if (d.life <= 0) { d.sp.material.opacity = 0; return; }
        d.life -= dt; d.v.y -= 9 * s * dt;
        d.sp.position.addScaledVector(d.v, dt);
        if (d.sp.position.y < 0) d.life = 0;
        d.sp.material.opacity = Math.max(0, Math.min(1, d.life * 1.5)) * 0.85;
        d.sp.scale.set(d.sz, d.sz, 1);
      });
      foams.forEach((f) => {
        if (f.life <= 0) { f.m.material.opacity = 0; return; }
        f.life -= dt;
        const k = 1 - f.life / 1.6;
        f.m.scale.setScalar((1.5 + 4 * k) * s);
        f.m.material.opacity = Math.max(0, 1 - k) * 0.9;
      });
      sparkles.forEach((sp) => {
        sp.sp.visible = airborne;
        sp.sp.position.set(heading.position.x + Math.cos(sp.a + t * fq(0.8)) * sp.rr * 1.3, heading.position.y + Math.sin(sp.a + t * fq(0.8)) * sp.rr * 0.7, heading.position.z + 0.8);
        sp.sp.material.opacity = 0.4 + 0.6 * Math.abs(Math.sin(t * fq(2) + sp.p));
        const sc = 0.18 * s; sp.sp.scale.set(sc, sc, 1);
      });
    };
  }

  // ---------------- 횡성: 해 질 녘 산골 목장, 한우, 곤포 사일리지 ----------------

  function paintField(g, w, h, wide, geo) {
    const { horizonY, pool } = geo;
    const sky = g.createLinearGradient(0, 0, 0, horizonY);
    sky.addColorStop(0, '#151B3A'); sky.addColorStop(0.55, '#3E3C66'); sky.addColorStop(0.85, '#A7697A'); sky.addColorStop(1, '#E7A25F');
    g.fillStyle = sky; g.fillRect(0, 0, w, horizonY + 1);
    drawStars(g, starList(w, horizonY * 0.35, wide ? 50 : 30, 8));
    bokeh(g, w * (wide ? 0.62 : 0.5), horizonY, h * 0.32, 0.35, '255,190,120');
    // 강원도 산 능선 세 겹. 멀수록 흐리다
    ridge(g, w, horizonY - h * 0.005, h * 0.16, 21, '#6E5E86');
    ridge(g, w, horizonY, h * 0.11, 13, '#463F69');
    const mist = g.createLinearGradient(0, horizonY - h * 0.06, 0, horizonY);
    mist.addColorStop(0, 'rgba(255,214,190,0)'); mist.addColorStop(1, 'rgba(255,214,190,0.22)');
    g.fillStyle = mist; g.fillRect(0, horizonY - h * 0.06, w, h * 0.06);
    ridge(g, w, horizonY + 2, h * 0.055, 4, '#2A2E4C');
    // 들판
    const field = g.createLinearGradient(0, horizonY, 0, h);
    field.addColorStop(0, '#4D5A33'); field.addColorStop(0.35, '#2D3B22'); field.addColorStop(1, '#0E170C');
    g.fillStyle = field; g.fillRect(0, horizonY, w, h - horizonY);
    // 풀결: 짧은 세로 획을 흩뿌린다(가까울수록 길게)
    const rnd = mulberry32(44);
    for (let i = 0; i < 900; i++) {
      const t = rnd(); const y = horizonY + 4 + (h - horizonY) * t * t;
      const len = h * (0.004 + 0.016 * t);
      g.strokeStyle = `rgba(${rnd() > 0.5 ? '150,170,90' : '20,35,15'},${0.12 + 0.2 * rnd()})`;
      g.lineWidth = 1 + t * 1.5;
      const x = rnd() * w; g.beginPath(); g.moveTo(x, y); g.lineTo(x + (rnd() - 0.5) * len * 0.4, y - len); g.stroke();
    }
    floorPool(g, pool, 'rgba(255,200,130,0.28)', 'rgba(200,170,90,0.10)');
  }

  // 노을 진 하늘을 천천히 오가는 구름(움직이는 배경만)
  function fieldFx(w, h, wide, geo) {
    const rnd = mulberry32(61);
    const clouds = Array.from({ length: wide ? 5 : 3 }, () => ({ x: rnd() * w, y: geo.horizonY * (0.15 + rnd() * 0.45), rx: w * (0.08 + rnd() * 0.08), ry: h * (0.015 + rnd() * 0.012), v: w * (0.01 + rnd() * 0.012), p: rnd() * 6.3, a: 0.1 + rnd() * 0.1 }));
    return (g, t) => clouds.forEach((c) => {
      const x = c.x + Math.sin(t * fq(0.3) + c.p) * c.v * 4;
      g.save(); g.translate(x, c.y); g.scale(1, c.ry / c.rx);
      const b = g.createRadialGradient(0, 0, 0, 0, 0, c.rx);
      b.addColorStop(0, `rgba(255,205,190,${c.a})`); b.addColorStop(1, 'rgba(255,205,190,0)');
      g.fillStyle = b; g.beginPath(); g.arc(0, 0, c.rx, 0, Math.PI * 2); g.fill();
      g.restore();
    });
  }

  async function buildField(scene, layout, grade, animated) {
    const wide = layout === 'landscape';
    const wood = new T.MeshStandardMaterial({ color: '#3E2716', roughness: 0.85, envMapIntensity: 0.3 });
    // 목장 울타리(화면과 나란하게)
    const fz = -9.5;
    for (let x = -24; x <= 24; x += 1.8) {
      const post = new T.Mesh(new T.BoxGeometry(0.14, 1.25, 0.14), wood); post.position.set(x, 0.62, fz); post.castShadow = true; scene.add(post);
    }
    [0.55, 1.0].forEach((y) => {
      const rail = new T.Mesh(new T.BoxGeometry(48, 0.1, 0.08), wood); rail.position.set(0, y, fz); rail.castShadow = true; scene.add(rail);
    });
    // 곤포 사일리지(흰 비닐로 싼 볏짚 뭉치)
    const wrap = new T.MeshStandardMaterial({ color: '#EDEFEA', roughness: 0.35, metalness: 0.0, envMapIntensity: 0.8 });
    const bales = wide
      ? [[4.5, -15, 0.3], [6.2, -16, 1.4], [5.3, -16.4, 0.8, true], [-9, -20, 0.2], [12, -24, 1.0], [-2.5, -26, 0.6]]
      : [[3.6, -16, 0.3], [5.2, -17, 1.4], [-4.5, -19, 0.6], [1.0, -26, 0.9]];
    bales.forEach(([x, z, yaw, stacked]) => {
      const b = new T.Mesh(new T.CylinderGeometry(0.7, 0.7, 1.25, 40), wrap);
      b.rotation.z = Math.PI / 2; const grp = new T.Group(); grp.add(b); grp.rotation.y = yaw;
      grp.position.set(x, stacked ? 2.05 : 0.7, z); b.castShadow = true; b.receiveShadow = true; scene.add(grp);
    });
    // 한우: 단상 왼쪽 뒤에서 화면 쪽을 비스듬히 본다
    const cow = await loadModel('hanwoo');
    cow.traverse((o) => {
      if (!o.isMesh) return;
      o.castShadow = true; o.receiveShadow = true;
      const m = o.material;
      if (m.name === 'Medal' || m.name === 'Ribbon') {
        const col = MEDAL[grade];
        if (!col) { o.visible = false; return; }
        if (m.name === 'Medal') { m.color = new T.Color(col); m.metalness = 1; m.roughness = 0.2; m.envMapIntensity = 1.5; }
        if (grade === 4 && m.name === 'Medal') { m.emissive = new T.Color('#F2B84B'); m.emissiveIntensity = 0.5; }
      } else if (m.name === 'HanwooHide') {
        m.color = new T.Color('#8A4E22'); m.envMapIntensity = 0.35;
      }
    });
    const s = wide ? 1.2 : 1.35;
    const g1 = new T.Group(); g1.add(cow); g1.scale.setScalar(s);
    // 머리(glTF -Z)가 화면 앞쪽을 비스듬히 보게(가슴의 메달이 보인다)
    g1.rotation.y = wide ? -2.55 : -2.75;
    g1.position.set(...(wide ? [-3.7, 0, -6.2] : [-2.8, 0, -6.4]));
    scene.add(g1);
    const rnd = mulberry32(77);
    const sparkleTex = radialTexture([[0, 'rgba(255,240,190,1)'], [0.3, 'rgba(255,220,140,0.5)'], [1, 'rgba(255,220,140,0)']]);
    let glow = null;
    const sparkles = [];
    if (grade === 4) {
      // 투뿔: 발밑에 금빛 고리와 반짝임
      glow = new T.Mesh(new T.PlaneGeometry(5 * s, 5 * s), new T.MeshBasicMaterial({
        map: radialTexture([[0, 'rgba(255,214,120,0.5)'], [0.45, 'rgba(255,200,90,0.35)'], [0.52, 'rgba(255,230,160,0.8)'], [0.58, 'rgba(255,200,90,0.2)'], [1, 'rgba(255,200,90,0)']]),
        transparent: true, depthWrite: false, toneMapped: false,
      }));
      glow.rotation.x = -Math.PI / 2; glow.position.set(g1.position.x, 0.02, g1.position.z); scene.add(glow);
      for (let i = 0; i < 18; i++) {
        const sp = sprite(sparkleTex, 0.6 + rnd() * 0.4, 2);
        const a = rnd() * Math.PI * 2, rr = 1.2 + rnd() * 1.4;
        sp.position.set(g1.position.x + Math.cos(a) * rr, 0.4 + rnd() * 2.6, g1.position.z + Math.sin(a) * rr * 0.6);
        const sc = 0.12 + rnd() * 0.2; sp.scale.set(sc, sc, 1); scene.add(sp);
        sparkles.push({ sp, p: rnd() * 6.3, y0: sp.position.y });
      }
    }
    const sun = new T.DirectionalLight(0xffb070, 1.0); sun.position.set(-6, 3, -20); scene.add(sun);
    if (!animated) return () => {};

    // 움직이는 배경: 들판 위를 떠도는 반딧불, 숨 쉬는 한우, 투뿔이면 금빛 고리가 맥박치듯 빛난다
    const flyTex = radialTexture([[0, 'rgba(230,255,170,1)'], [0.3, 'rgba(190,255,120,0.5)'], [1, 'rgba(190,255,120,0)']]);
    const flies = Array.from({ length: wide ? 26 : 16 }, () => {
      const sp = sprite(flyTex, 0, 2); scene.add(sp);
      return { sp, x: (rnd() - 0.5) * (wide ? 22 : 14), z: -3 - rnd() * 12, y: 0.4 + rnd() * 2.2, p: rnd() * 6.3, f: 0.3 + rnd() * 0.5 };
    });
    return (t) => {
      g1.scale.set(s, s * (1 + 0.012 * Math.sin(t * fq(1.8))), s);
      flies.forEach((f) => {
        f.sp.position.set(f.x + Math.sin(t * fq(f.f) + f.p) * 1.2, f.y + Math.sin(t * fq(f.f * 1.7) + f.p) * 0.4, f.z + Math.cos(t * fq(f.f * 0.8) + f.p) * 0.8);
        f.sp.material.opacity = Math.max(0, Math.sin(t * fq(1.3) + f.p * 3)) * 0.95;
        f.sp.scale.set(0.16, 0.16, 1);
      });
      if (glow) glow.material.opacity = 0.75 + 0.25 * Math.sin(t * fq(2));
      sparkles.forEach((k) => { k.sp.material.opacity = 0.3 + 0.7 * Math.abs(Math.sin(t * fq(1.5) + k.p)); k.sp.position.y = k.y0 + 0.15 * Math.sin(t * fq(0.8) + k.p); });
    };
  }

  // ---------------- 정지 그림(tools/render3d) ----------------

  async function renderVehicle(name, w, h, layout) {
    const canvas = document.getElementById('cv'); canvas.width = w; canvas.height = h;
    const r = makeRenderer(canvas, w, h, 2048);
    const scene = makeScene(r);
    const { cam } = makeCamera(w, h, layout);
    addLights(scene, 2048);
    shadowGround(scene);
    buildStage(scene);
    await buildVehicle(scene, name);
    r.render(scene, cam);
    const url = canvas.toDataURL('image/png');
    r.dispose();
    return url;
  }

  // 주행 화면 탈것: 단상 없이 땅 위에서 화면 안쪽(배경 지평선의 정면 소실점)으로 달려가는 뒷모습
  async function renderVehicleRear(name, w, h, layout) {
    const canvas = document.getElementById('cv'); canvas.width = w; canvas.height = h;
    const r = makeRenderer(canvas, w, h, 2048);
    const scene = makeScene(r);
    const { cam } = makeCamera(w, h, layout);
    addLights(scene, 2048);
    shadowGround(scene, 0.5);
    // 주행 계기판(화면 아래)에 가리지 않게 단상 자리보다 앞으로 낸다(울산 물가 z=-6 바로 앞)
    (await buildVehicle(scene, name, REAR_YAW, 0)).position.z = REAR_Z;
    r.render(scene, cam);
    const url = canvas.toDataURL('image/png');
    r.dispose();
    return url;
  }

  async function renderBackground(region, grade, w, h, layout) {
    const wide = layout === 'landscape';
    const canvas = document.getElementById('cv'); canvas.width = w; canvas.height = h;
    const r = makeRenderer(canvas, w, h, 2048);
    const scene = makeScene(r);
    const { cam, geo } = makeCamera(w, h, layout);
    const out = document.createElement('canvas'); out.width = w; out.height = h;
    const g = out.getContext('2d');
    if (region === 'ulsan') {
      paintSea(g, w, h, wide, geo, false);
      addLights(scene, 2048, 0xdfe8ff);
      await buildSea(scene, layout, grade, false);
    } else if (region === 'hoengseong') {
      paintField(g, w, h, wide, geo);
      addLights(scene, 2048, 0xffd2a0);
      shadowGround(scene, 0.35);
      await buildField(scene, layout, grade, false);
    } else if (window.TOWNS[region]) {
      const town = window.TOWNS[region];
      town.paint(g, w, h, wide, geo, false);
      addLights(scene, 2048, town.key);
      shadowGround(scene, 0.35);
      await town.build(scene, layout, false, geo);
    } else {
      paintPalace(g, w, h, wide, geo, false);
    }
    r.render(scene, cam);
    g.drawImage(canvas, 0, 0);
    vignette(g, w, h, geo);
    r.dispose();
    return out.toDataURL('image/png');
  }

  // 가장자리를 어둡게 눌러 카드 글씨가 잘 읽히게 한다
  function vignette(g, w, h, geo) {
    const vig = g.createRadialGradient(geo.pool.x, h * 0.5, Math.min(w, h) * 0.35, geo.pool.x, h * 0.5, Math.max(w, h) * 0.85);
    vig.addColorStop(0, 'rgba(0,0,0,0)'); vig.addColorStop(1, 'rgba(0,0,0,0.45)');
    g.fillStyle = vig; g.fillRect(0, 0, w, h);
  }

  // 도감·해금 연출용 탈것 아이콘(정사각, 투명 배경). 단상 없이 탈것과 발밑 그림자만
  async function renderIcon(name, size) {
    const canvas = document.getElementById('cv'); canvas.width = canvas.height = size;
    const r = makeRenderer(canvas, size, size, 1024);
    r.toneMappingExposure = 1.1;
    const scene = makeScene(r);
    scene.add(new T.HemisphereLight(0xffffff, 0x334444, 0.6));
    const key = new T.DirectionalLight(0xffe2c0, 1.6); key.position.set(4, 8, 6); scene.add(key);
    const rim = new T.DirectionalLight(0x9cc4ff, 1.0); rim.position.set(-5, 4, -6); scene.add(rim);
    const model = await loadModel(name);
    model.traverse((o) => { if (o.isMesh && o.material) o.material.envMapIntensity = 0.7; });
    const pivot = new T.Group(); pivot.add(model); pivot.rotation.y = YAW; scene.add(pivot);
    const box = new T.Box3().setFromObject(pivot);
    const c = box.getCenter(new T.Vector3()), sz = box.getSize(new T.Vector3());
    pivot.position.set(-c.x, -box.min.y, -c.z);
    const shadow = new T.Mesh(new T.PlaneGeometry(sz.x * 1.1, sz.z * 1.1), new T.MeshBasicMaterial({
      map: radialTexture([[0, 'rgba(0,0,0,0.45)'], [1, 'rgba(0,0,0,0)']]), transparent: true, depthWrite: false, toneMapped: false,
    }));
    shadow.rotation.x = -Math.PI / 2; shadow.position.y = 0.001; scene.add(shadow);
    // 모델 크기에 맞춰 화면을 채우도록 카메라 거리를 정한다
    const radius = Math.max(sz.x, sz.y, sz.z) * 0.63;
    const cam = new T.PerspectiveCamera(30, 1, 0.1, 200);
    const d = radius / Math.tan(T.MathUtils.degToRad(15));
    cam.position.set(0, sz.y * 0.5 + d * 0.32, d * 0.95); cam.lookAt(0, sz.y * 0.5, 0);
    r.render(scene, cam);
    const url = canvas.toDataURL('image/png');
    r.dispose();
    return url;
  }

  /**
   * opt: { kind: 'vehicle', name } 또는 { kind: 'background', region: null|'ulsan'|'hoengseong', grade } + w, h, layout,
   *      { kind: 'icon', name, w } (정사각 아이콘)
   */
  // 앱 실행 아이콘: 짙은 청록 바탕에 금빛 고리, 가운데 가마
  async function renderAppIcon(size) {
    const img = new Image();
    img.src = await renderIcon('gama', size);
    await img.decode();
    const c = document.createElement('canvas'); c.width = c.height = size;
    const g = c.getContext('2d');
    const bg = g.createRadialGradient(size / 2, size * 0.45, 0, size / 2, size / 2, size * 0.72);
    bg.addColorStop(0, '#1B4F4A'); bg.addColorStop(1, '#061A19');
    g.fillStyle = bg; g.fillRect(0, 0, size, size);
    g.save();
    g.shadowColor = 'rgba(242,184,75,0.8)'; g.shadowBlur = size * 0.04;
    g.strokeStyle = '#F2B84B'; g.lineWidth = size * 0.022;
    g.beginPath(); g.arc(size / 2, size / 2, size * 0.40, 0, Math.PI * 2); g.stroke();
    g.restore();
    const s = size * 0.74;
    g.drawImage(img, (size - s) / 2, (size - s) / 2 + size * 0.02, s, s);
    return c.toDataURL('image/png');
  }

  // 도감 지역 장면(towns.js)이 쓰는 도우미
  window.Stage = { T, mulberry32, fq, radialTexture, sprite, bokeh, starList, drawStars, floorPool, ridge, drawHall, LOOP_SEC };
  window.TOWNS = window.TOWNS || {};

  window.renderLayer = function (opt) {
    if (opt.kind === 'icon') return renderIcon(opt.name, opt.w);
    if (opt.kind === 'appicon') return renderAppIcon(opt.w);
    if (opt.kind === 'rear') return renderVehicleRear(opt.name, opt.w, opt.h, opt.layout);
    return opt.kind === 'vehicle'
      ? renderVehicle(opt.name, opt.w, opt.h, opt.layout)
      : renderBackground(opt.region, opt.grade || 0, opt.w, opt.h, opt.layout);
  };

  // ---------------- 움직이는 배경(루프 영상 프레임) ----------------

  /**
   * Anim.setup({ region, grade, w, h, layout })로 장면을 만들고 Anim.frame(t)로 t초의 프레임을 PNG로 받는다.
   * 탈것·단상은 넣지 않는다(앱이 탈것 레이어를 위에 겹친다). 입자가 이어지도록 프레임은 시간 순서대로 부른다.
   * 첫 프레임 시각은 Anim.start: 고래가 잠긴 뒤 물보라가 다 가라앉은 때라 루프 이음매에 입자가 없다.
   */
  window.Anim = (function () {
    let st = null;
    return {
      LOOP_SEC,
      // 물기둥까지 가라앉고 고래가 다시 뜨기 직전(입자가 하나도 없는 때)
      start: (0.97 - STILL_U * AIR) * BREACH_SEC,
      /** 첫 프레임에서 정지 그림과 같은 순간(고래가 떠오르는 중)까지의 시간(초). 앱이 처음 재생할 때 여기로 건너뛴다. */
      stillSec: (((1 - (0.97 - STILL_U * AIR)) % 1) * BREACH_SEC),
      async setup(opt) {
        const { region, w, h, layout } = opt;
        const grade = opt.grade || 0, wide = layout === 'landscape';
        rand = mulberry32(99);
        const gl = document.getElementById('cv'); gl.width = w; gl.height = h;
        if (st) st.r.dispose();
        const r = makeRenderer(gl, w, h, 2048);
        const scene = makeScene(r);
        const { cam, geo } = makeCamera(w, h, layout);
        const bg = document.createElement('canvas'); bg.width = w; bg.height = h;
        const fx = document.createElement('canvas'); fx.width = w; fx.height = h;
        const out = document.createElement('canvas'); out.width = w; out.height = h;
        const g = bg.getContext('2d');
        const updates = [];
        let paintFx;
        if (region === 'ulsan') {
          paintSea(g, w, h, wide, geo, true);
          addLights(scene, 2048, 0xdfe8ff);
          updates.push(await buildSea(scene, layout, grade, true));
          paintFx = seaFx(w, h, wide, geo);
        } else if (region === 'hoengseong') {
          paintField(g, w, h, wide, geo);
          addLights(scene, 2048, 0xffd2a0);
          shadowGround(scene, 0.35);
          updates.push(await buildField(scene, layout, grade, true));
          paintFx = fieldFx(w, h, wide, geo);
        } else if (window.TOWNS[region]) {
          const town = window.TOWNS[region];
          town.paint(g, w, h, wide, geo, true);
          addLights(scene, 2048, town.key);
          shadowGround(scene, 0.35);
          updates.push(await town.build(scene, layout, true, geo));
          paintFx = town.fx(w, h, wide, geo);
        } else {
          paintPalace(g, w, h, wide, geo, true);
          paintFx = palaceFx(w, h, wide, geo);
        }
        st = { r, scene, cam, geo, bg, fx, out, updates, paintFx, w, h };
      },
      frame(t) {
        const { r, scene, cam, geo, bg, fx, out, updates, paintFx, w, h } = st;
        updates.forEach((u) => u(t));
        const f = fx.getContext('2d');
        f.clearRect(0, 0, w, h);
        paintFx(f, t);
        r.render(scene, cam);
        const o = out.getContext('2d');
        o.drawImage(bg, 0, 0); o.drawImage(fx, 0, 0); o.drawImage(r.domElement, 0, 0);
        vignette(o, w, h, geo);
        return out.toDataURL('image/png');
      },
    };
  })();
})();
