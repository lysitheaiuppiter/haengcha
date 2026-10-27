# 행차 지역 테마 동물(울산 고래, 횡성 한우)을 코드로 모델링해 glTF로 내보낸다(Blender 5.2, 파이썬 모듈 bpy).
# 재질 이름(WhaleSkin, Medal 등)은 정차 화면 렌더(tools/render3d/stage.js)가 단골 등급에 따라 색을 바꿀 때 찾는다.
# 예) python tools/models/region_set.py --out assets3d/models -- whale hanwoo
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from joseon_set import *  # noqa: F401,F403  재질·도형 도우미와 소 모델
import argparse

M.update(
    skin=mat('WhaleSkin', (0.035, 0.13, 0.36), rough=0.35, coat=0.5),
    belly=mat('WhaleBelly', (0.86, 0.88, 0.9), rough=0.45),
    groove=mat('WhaleGroove', (0.68, 0.74, 0.82), rough=0.45),
    mouth=mat('WhaleMouth', (0.01, 0.03, 0.08), rough=0.5),
    hanwoo=mat('HanwooHide', (0.33, 0.12, 0.035), rough=0.65),
    medal=mat('Medal', (1.0, 0.71, 0.29), metal=1.0, rough=0.25),
    ribbon=mat('Ribbon', (0.32, 0.02, 0.03), rough=0.6),
)


def loft(name, rings, seg, m, paint=None):
    """원형 단면(rx, rz)을 y축을 따라 이어 붙인 몸통. rings: (y, cz, rx, rz). 양 끝은 한 점으로 닫는다.
    paint(t, a)가 재질 이름을 돌려주면 그 면에 그 재질을 칠한다(t는 꼬리 0 ~ 머리 1, a는 단면 각도)."""
    bm = bmesh.new()
    vs = []
    for y, cz, rx, rz in rings:
        vs.append([bm.verts.new((rx * math.cos(2 * math.pi * k / seg), y, cz + rz * math.sin(2 * math.pi * k / seg))) for k in range(seg)])
    tail = bm.verts.new((0, rings[0][0] - 0.03, rings[0][1]))
    nose = bm.verts.new((0, rings[-1][0] + 0.06, rings[-1][1]))
    faces = []
    for i in range(len(vs) - 1):
        for k in range(seg):
            f = bm.faces.new((vs[i][k], vs[i + 1][k], vs[i + 1][(k + 1) % seg], vs[i][(k + 1) % seg]))
            faces.append((f, (i + 0.5) / (len(vs) - 1), 2 * math.pi * (k + 0.5) / seg))
    for k in range(seg):
        bm.faces.new((tail, vs[0][k], vs[0][(k + 1) % seg]))
        bm.faces.new((nose, vs[-1][(k + 1) % seg], vs[-1][k]))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    names = [m]
    if paint:
        for f, t, a in faces:
            n = paint(t, a)
            if n and n != m:
                if n not in names: names.append(n)
                f.material_index = names.index(n)
    me = bpy.data.meshes.new(name); bm.to_mesh(me); bm.free()
    o = bpy.data.objects.new(name, me); bpy.context.scene.collection.objects.link(o)
    for n in names: o.data.materials.append(M[n])
    for p in o.data.polygons: p.use_smooth = True
    md = o.modifiers.new('s', 'SUBSURF'); md.levels = 1; md.render_levels = 1
    return link(o)


def flat(name, outline, thick, loc, m):
    """평면 윤곽(x, y)을 두께만큼 세운 판. 고래 꼬리지느러미에 쓴다."""
    bm = bmesh.new()
    bm.faces.new([bm.verts.new((x, y, 0)) for x, y in outline])
    me = bpy.data.meshes.new(name); bm.to_mesh(me); bm.free()
    o = bpy.data.objects.new(name, me); bpy.context.scene.collection.objects.link(o); o.location = loc
    o.data.materials.append(M[m])
    for p in o.data.polygons: p.use_smooth = True
    sol = o.modifiers.new('t', 'SOLIDIFY'); sol.thickness = thick; sol.offset = 0
    sub = o.modifiers.new('s', 'SUBSURF'); sub.levels = 2; sub.render_levels = 2
    bpy.context.view_layer.objects.active = o; o.select_set(True)
    bpy.ops.object.modifier_apply(modifier='t'); bpy.ops.object.modifier_apply(modifier='s')
    return link(o)


def build_whale():
    # 장생포 고래를 닮은 귀여운 고래. 머리가 +Y, 길이 4.0
    L, R, N, SEG = 4.0, 0.72, 26, 32

    def radius(t):
        if t >= 0.6: return R * math.sqrt(max(0.0, 1 - ((t - 0.6) / 0.42) ** 2))
        return R * (0.1 + 0.9 * math.sin(math.pi / 2 * t / 0.6) ** 1.5)

    rings = []
    for i in range(N + 1):
        t = 0.985 * i / N
        r = max(radius(t), 0.06)
        rings.append((-L / 2 + t * L, 0.12 * (1 - t) ** 2, r, r * 0.84))

    def paint(t, a):
        s = math.sin(a)
        if s < -0.42 and 0.22 < t:
            # 배 쪽 주름(줄무늬)은 앞쪽 절반에만
            if t > 0.5 and int(a / (2 * math.pi) * SEG) % 2 == 0: return 'groove'
            return 'belly'
        return None

    parts = [loft('WhaleBody', rings, SEG, 'skin', paint)]
    # 꼬리지느러미
    half = [(0.0, 0.18), (0.32, 0.06), (0.72, -0.12), (1.02, -0.42), (0.86, -0.46), (0.52, -0.34), (0.2, -0.36), (0.0, -0.22)]
    outline = half + [(-x, y) for x, y in reversed(half[1:-1])]
    parts.append(flat('Fluke', outline, 0.07, (0, -L / 2 - 0.02, rings[0][1]), 'skin'))
    # 가슴지느러미(혹등고래처럼 긴 편)
    for sx in (-1, 1):
        f = sph('Flipper', 0.5, (sx * 0.78, 0.35, -0.3), 'skin', (0.32, 1.45, 0.07))
        f.rotation_euler = (math.radians(-12), math.radians(sx * -38), math.radians(sx * 28)); bpy.context.view_layer.objects.active = f
        bpy.ops.object.select_all(action='DESELECT'); f.select_set(True); bpy.ops.object.transform_apply(rotation=True)
        parts.append(f)
    # 등지느러미
    parts.append(sph('Dorsal', 0.3, (0, -0.75, rings[8][1] + rings[8][3] * 0.95), 'skin', (0.18, 0.9, 0.55)))
    # 눈과 웃는 입
    for sx in (-1, 1):
        t = 0.84; r = radius(t); y = -L / 2 + t * L
        a = math.radians(-8)
        parts.append(sph('Eye', 0.07, (sx * (r * math.cos(a) + 0.01), y, r * 0.84 * math.sin(a)), 'black'))
        parts.append(sph('Glint', 0.022, (sx * (r * math.cos(a) + 0.06), y + 0.03, r * 0.84 * math.sin(a) + 0.03), 'white'))
        pts = []
        for j in range(9):
            tt = 0.975 - 0.2 * j / 8
            rr = radius(tt); aa = math.radians(-30 + 16 * (j / 8) ** 2)  # 뒤끝이 올라가는 웃는 입
            pts.append((sx * (rr * math.cos(aa) + 0.012), -L / 2 + tt * L, rr * 0.84 * math.sin(aa)))
        for p0, p1 in zip(pts, pts[1:]): parts.append(cyl('Mouth', 0.02, p0, p1, 'mouth', 8))
        for p in pts[1:-1]: parts.append(sph('MouthJoint', 0.02, p, 'mouth'))
    return [join(parts, 'Whale', origin=Vector((0, 0, 0)))]


def build_hanwoo():
    # 횡성 한우. 소달구지의 소와 같은 모양에 털색을 짙게 하고 코뚜레와 단골 메달을 단다
    body = join(ox_parts(0.0, 'hanwoo'), 'Hanwoo', origin=Vector((0, 0, 0)))
    ring = torus('NoseRing', 0.09, 0.016, (0, 1.53, 1.06), 'iron', rot=(0, math.pi / 2, 0))
    # 목에 건 메달: 붉은 끈 두 가닥과 원판. 등급에 따라 렌더에서 색을 바꾸거나 숨긴다
    ribbon = join([cyl('RibbonL', 0.03, (-0.16, 0.9, 1.45), (-0.05, 1.12, 0.88), 'ribbon', 8),
                   cyl('RibbonR', 0.03, (0.16, 0.9, 1.45), (0.05, 1.12, 0.88), 'ribbon', 8)], 'Ribbon', origin=Vector((0, 1.0, 1.1)))
    medal = cyl('Medal', 0.15, (0, 1.12, 0.82), (0, 1.17, 0.82), 'medal', 32)
    return [body, ring, ribbon, medal]


def main():
    argv = sys.argv[1:]
    names = argv[argv.index('--') + 1:] if '--' in argv else ['whale', 'hanwoo']
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='assets3d/models')
    args, _ = ap.parse_known_args(argv[:argv.index('--')] if '--' in argv else argv)
    os.makedirs(args.out, exist_ok=True)
    for name in names:
        CUR[0] = coll(name)
        {'whale': build_whale, 'hanwoo': build_hanwoo}[name]()
        export(name, args.out)


if __name__ == '__main__':
    main()
