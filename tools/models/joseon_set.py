# 행차 조선 테마 탈것을 코드로 모델링해 glTF로 내보낸다(Blender 5.2, 파이썬 모듈 bpy).
# 소개 영상 프로젝트(haengcha3-hyperframes)의 스크립트에 Lv.0 소달구지를 더했다.
# 예) python tools/models/joseon_set.py --out assets3d/models -- ox_cart
import bpy, bmesh, math
from mathutils import Vector
bpy.ops.wm.read_factory_settings(use_empty=True)
def mat(name, col, metal=0.0, rough=0.5, coat=0.0, emit=None, sheen=0.0):
    m = bpy.data.materials.new(name); m.use_nodes = True; b = m.node_tree.nodes['Principled BSDF']
    b.inputs['Base Color'].default_value = (*col, 1); b.inputs['Metallic'].default_value = metal; b.inputs['Roughness'].default_value = rough
    if coat: b.inputs['Coat Weight'].default_value = coat; b.inputs['Coat Roughness'].default_value = 0.06
    if emit: b.inputs['Emission Color'].default_value = (*emit, 1); b.inputs['Emission Strength'].default_value = 4.0
    return m
M = dict(lac=mat('Lacquer', (0.42, 0.025, 0.02), rough=0.25, coat=1.0), pink=mat('PinkLacquer', (0.80, 0.18, 0.35), rough=0.3, coat=0.8),
         gold=mat('Gold', (1.0, 0.71, 0.29), metal=1.0, rough=0.3), wood=mat('Wood', (0.36, 0.18, 0.07), rough=0.55),
         dkwood=mat('DarkWood', (0.12, 0.05, 0.025), rough=0.5), tile=mat('RoofTile', (0.05, 0.065, 0.085), rough=0.45, coat=0.4),
         goldroof=mat('GoldRoof', (0.95, 0.62, 0.18), metal=0.85, rough=0.35), fabric=mat('Hood', (0.03, 0.045, 0.075), rough=0.8),
         iron=mat('Iron', (0.04, 0.04, 0.045), metal=0.7, rough=0.45), cush=mat('Cushion', (0.28, 0.025, 0.03), rough=0.75),
         paper=mat('Paper', (0.92, 0.86, 0.72), rough=0.9), lamp=mat('Lamp', (1.0, 0.8, 0.5), rough=0.3, emit=(1.0, 0.6, 0.22)),
         petalP=mat('PetalPink', (1.0, 0.45, 0.62), rough=0.5), petalW=mat('PetalWhite', (0.98, 0.95, 0.92), rough=0.5), stamen=mat('Stamen', (1.0, 0.75, 0.15), rough=0.5),
         blue=mat('Blue', (0.05, 0.22, 0.75), rough=0.5), red=mat('Red', (0.8, 0.06, 0.04), rough=0.5), yellow=mat('Yellow', (1.0, 0.72, 0.08), rough=0.5),
         green=mat('Green', (0.04, 0.5, 0.25), rough=0.5), white=mat('White', (0.95, 0.95, 0.93), rough=0.5), black=mat('Black', (0.02, 0.02, 0.025), rough=0.5),
         straw=mat('Straw', (0.78, 0.6, 0.27), rough=0.9), hide=mat('Hide', (0.42, 0.2, 0.08), rough=0.7, sheen=0.3),
         muzzle=mat('Muzzle', (0.22, 0.13, 0.1), rough=0.6), horn=mat('Horn', (0.86, 0.8, 0.66), rough=0.4))
def put(o, m, smooth=True):
    o.data.materials.append(M[m])
    if smooth:
        for p in o.data.polygons: p.use_smooth = True
    return o
def coll(name):
    c = bpy.data.collections.new(name); bpy.context.scene.collection.children.link(c); return c
CUR = [None]
def link(o):
    for c in o.users_collection: c.objects.unlink(o)
    CUR[0].objects.link(o); return o
def box(name, size, loc, m, bevel=0.02):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc); o = bpy.context.object; o.name = name; o.scale = size; bpy.ops.object.transform_apply(scale=True)
    if bevel: md = o.modifiers.new('b', 'BEVEL'); md.width = bevel; md.segments = 3; bpy.ops.object.modifier_apply(modifier='b')
    return link(put(o, m))
def cyl(name, r, a, b, m, verts=24, r2=None):
    a, b = Vector(a), Vector(b); d = b - a
    if r2 is None: bpy.ops.mesh.primitive_cylinder_add(radius=r, depth=d.length, vertices=verts, location=(a + b) / 2)
    else: bpy.ops.mesh.primitive_cone_add(radius1=r, radius2=r2, depth=d.length, vertices=verts, location=(a + b) / 2)
    o = bpy.context.object; o.name = name; o.rotation_mode = 'QUATERNION'; o.rotation_quaternion = d.to_track_quat('Z', 'Y'); bpy.ops.object.transform_apply(rotation=True)
    return link(put(o, m))
def sph(name, r, loc, m, scale=(1, 1, 1)):
    bpy.ops.mesh.primitive_uv_sphere_add(radius=r, location=loc, segments=24, ring_count=12); o = bpy.context.object; o.name = name; o.scale = scale; bpy.ops.object.transform_apply(scale=True)
    return link(put(o, m))
def torus(name, R, r, loc, m, rot=(0, math.pi / 2, 0)):
    bpy.ops.mesh.primitive_torus_add(major_radius=R, minor_radius=r, major_segments=64, minor_segments=12, location=loc, rotation=rot)
    o = bpy.context.object; o.name = name; bpy.ops.object.transform_apply(rotation=True); return link(put(o, m))
def join(objs, name, origin=None):
    bpy.ops.object.select_all(action='DESELECT')
    for o in objs: o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]; bpy.ops.object.join(); o = bpy.context.object; o.name = name
    if origin is not None:
        bpy.context.scene.cursor.location = origin; bpy.ops.object.origin_set(type='ORIGIN_CURSOR')
    return o
def roof(name, w, d, h, eave, m, curl=0.22, base_z=0.0):
    # hipped Korean roof: grid surface with sagging slopes and upturned corners
    bm = bmesh.new(); nx, ny = 28, 20; W, D = w / 2 + eave, d / 2 + eave; vs = []
    for j in range(ny + 1):
        row = []
        for i in range(nx + 1):
            u = -1 + 2 * i / nx; v = -1 + 2 * j / ny; x, y = u * W, v * D
            r = max(abs(u), abs(v)); z = h * (1 - r) ** 1.35
            z += curl * (abs(u) * abs(v)) ** 3.0 + 0.06 * (abs(u) ** 6 + abs(v) ** 6)
            row.append(bm.verts.new((x, y, base_z + z)))
        vs.append(row)
    for j in range(ny):
        for i in range(nx): bm.faces.new((vs[j][i], vs[j][i + 1], vs[j + 1][i + 1], vs[j + 1][i]))
    me = bpy.data.meshes.new(name); bm.to_mesh(me); o = bpy.data.objects.new(name, me); CUR[0].objects.link(o)
    bpy.context.view_layer.objects.active = o; bpy.ops.object.select_all(action='DESELECT'); o.select_set(True)
    md = o.modifiers.new('s', 'SOLIDIFY'); md.thickness = 0.05; bpy.ops.object.modifier_apply(modifier='s')
    md = o.modifiers.new('sub', 'SUBSURF'); md.levels = 1; bpy.ops.object.modifier_apply(modifier='sub')
    return put(o, m)
def lattice_window(cx, y, cz, w, h, m_frame='gold', m_paper='paper', name='Win', facing='y'):
    parts = [box(name + 'Paper', (w, 0.02, h) if facing == 'y' else (0.02, w, h), (cx, y, cz), m_paper, 0)]
    nb = 3
    for k in range(1, nb):
        dx = -w / 2 + k * w / nb; dz = -h / 2 + k * h / nb
        if facing == 'y':
            parts += [box(name + 'V', (0.025, 0.035, h), (cx + dx, y, cz), m_frame, 0.005), box(name + 'H', (w, 0.035, 0.025), (cx, y, cz + dz), m_frame, 0.005)]
        else:
            parts += [box(name + 'V', (0.035, 0.025, h), (cx, y + dx, cz), m_frame, 0.005), box(name + 'H', (0.035, w, 0.025), (cx, y, cz + dz), m_frame, 0.005)]
    t = 0.045
    if facing == 'y':
        parts += [box(name + 'FT', (w + 2 * t, 0.045, t), (cx, y, cz + h / 2 + t / 2), m_frame, 0.008), box(name + 'FB', (w + 2 * t, 0.045, t), (cx, y, cz - h / 2 - t / 2), m_frame, 0.008),
                  box(name + 'FL', (t, 0.045, h), (cx - w / 2 - t / 2, y, cz), m_frame, 0.008), box(name + 'FR', (t, 0.045, h), (cx + w / 2 + t / 2, y, cz), m_frame, 0.008)]
    else:
        parts += [box(name + 'FT', (0.045, w + 2 * t, t), (cx, y, cz + h / 2 + t / 2), m_frame, 0.008), box(name + 'FB', (0.045, w + 2 * t, t), (cx, y, cz - h / 2 - t / 2), m_frame, 0.008),
                  box(name + 'FL', (0.045, t, h), (cx, y - w / 2 - t / 2, cz), m_frame, 0.008), box(name + 'FR', (0.045, t, h), (cx, y + w / 2 + t / 2, cz), m_frame, 0.008)]
    return parts
def flower(name, loc, r, petal):
    ps = []
    for k in range(5):
        a = k * 2 * math.pi / 5; c = Vector(loc) + Vector((math.cos(a) * r * 0.55, math.sin(a) * r * 0.55, 0))
        ps.append(sph(name + 'P', r * 0.42, c, petal, (1.25, 1.25, 0.45)))
    ps.append(sph(name + 'C', r * 0.26, Vector(loc) + Vector((0, 0, 0.02)), 'stamen', (1, 1, 0.7)))
    return join(ps, name)
def tassel(name, top, colors):
    out = []
    for k, c in enumerate(colors):
        o = Vector(top) + Vector((0, (k - 2) * 0.035, 0))
        out.append(cyl(name + 'Cord', 0.014, o, o - Vector((0, 0, 0.32)), c, 8)); out.append(cyl(name + 'Tip', 0.045, o - Vector((0, 0, 0.32)), o - Vector((0, 0, 0.52)), c, 12, 0.012))
    return join(out, name, origin=Vector(top))

def build_gama(kind):
    body = 'pink' if kind == 'kkot' else 'lac'
    W, D, H = 1.0, 1.0, 1.15; z0 = 0.5
    parts = [box('Base', (W + 0.16, D + 0.16, 0.12), (0, 0, z0), 'dkwood', 0.02)]
    parts.append(box('Body', (W, D, H), (0, 0, z0 + 0.06 + H / 2), body, 0.03))
    for sx in (-1, 1):
        for sy in (-1, 1): parts.append(cyl('Post', 0.04, (sx * W / 2, sy * D / 2, z0 + 0.06), (sx * W / 2, sy * D / 2, z0 + 0.06 + H), 'gold' if kind == 'eoga' else 'dkwood', 12))
    parts.append(box('Belt', (W + 0.04, D + 0.04, 0.06), (0, 0, z0 + 0.06 + H - 0.08), 'gold', 0.01))
    parts.append(box('Belt2', (W + 0.04, D + 0.04, 0.05), (0, 0, z0 + 0.2), 'gold', 0.01))
    for sx in (-1, 1):
        for sy in (-1, 1): parts.append(box('Foot', (0.1, 0.1, z0 - 0.06), (sx * (W / 2 - 0.02), sy * (D / 2 - 0.02), (z0 - 0.06) / 2), 'dkwood', 0.01))
    parts += lattice_window(0, D / 2 + 0.012, z0 + 0.06 + H * 0.55, 0.62, 0.62, name='Door')
    parts += lattice_window(W / 2 + 0.012, 0, z0 + 0.06 + H * 0.6, 0.44, 0.38, name='SideR', facing='x')
    parts += lattice_window(-W / 2 - 0.012, 0, z0 + 0.06 + H * 0.6, 0.44, 0.38, name='SideL', facing='x')
    rz = z0 + 0.06 + H
    rf = roof('Roof', W, D, 0.55 if kind != 'eoga' else 0.62, 0.32, 'goldroof' if kind == 'eoga' else 'tile', 0.26, rz)
    parts.append(rf)
    parts.append(box('Ridge', (0.26, 0.26, 0.08), (0, 0, rz + (0.55 if kind != 'eoga' else 0.62) - 0.02), 'gold', 0.02))
    parts.append(sph('Finial', 0.11, (0, 0, rz + (0.55 if kind != 'eoga' else 0.62) + 0.12), 'gold'))
    if kind == 'eoga': parts.append(cyl('FinialTip', 0.06, (0, 0, rz + 0.84), (0, 0, rz + 1.02), 'gold', 16, 0.0))
    # carrying poles along Y
    for sx in (-0.74, 0.74):
        parts.append(cyl('Pole', 0.045, (sx, -1.55, z0 + 0.35), (sx, 1.55, z0 + 0.35), 'wood'))
        for sy in (-1.55, 1.55): parts.append(sph('PoleCap', 0.06, (sx, sy, z0 + 0.35), 'gold'))
        for sy in (-0.35, 0.35): parts.append(box('Bracket', (0.3, 0.06, 0.06), (sx * 0.83, sy, z0 + 0.35), 'gold', 0.01))
    body_obj = join(parts, 'Gama', origin=Vector((0, 0, 0)))
    extras = []
    if kind == 'kkot':
        fl = []
        for (x, y, z, r, p) in [(0, 0, rz + 0.78, 0.3, 'petalW'), (0.34, 0.34, rz + 0.36, 0.24, 'petalP'), (-0.34, 0.34, rz + 0.36, 0.24, 'petalP'),
                                (0.34, -0.34, rz + 0.36, 0.24, 'petalP'), (-0.34, -0.34, rz + 0.36, 0.24, 'petalP'), (0, 0.56, rz + 0.26, 0.2, 'petalW'),
                                (0.56, 0, rz + 0.26, 0.2, 'petalW'), (-0.56, 0, rz + 0.26, 0.2, 'petalW'), (0, -0.56, rz + 0.26, 0.2, 'petalW')]:
            fl.append(flower('Flower', (x, y, z), r, p))
        for i, f in enumerate(fl): f.name = f'Flower{i}'
        extras += fl
        for i, (sx, sy) in enumerate([(-1, -1), (1, -1), (1, 1), (-1, 1)]):
            t = tassel(f'Tassel{i}', (sx * (W / 2 + 0.36), sy * (D / 2 + 0.36), rz + 0.12), ['blue', 'red', 'yellow', 'green', 'white']); extras.append(t)
    if kind == 'eoga':
        # parasol (일산) on a pole behind, two banners
        pp = [cyl('ParasolPole', 0.025, (0, -1.45, 0.0), (0, -1.45, 3.1), 'wood', 12)]
        bpy.ops.mesh.primitive_cone_add(radius1=0.95, radius2=0.05, depth=0.38, vertices=48, location=(0, -1.45, 3.05)); cone = link(put(bpy.context.object, 'red'))
        md = cone.modifiers.new('s', 'SOLIDIFY'); md.thickness = 0.02; bpy.context.view_layer.objects.active = cone; bpy.ops.object.modifier_apply(modifier='s')
        fr = torus('Fringe', 0.95, 0.025, (0, -1.45, 2.87), 'gold', rot=(0, 0, 0))
        drops = [sph('Drop', 0.035, (0.95 * math.cos(a), -1.45 + 0.95 * math.sin(a), 2.8), 'gold') for a in [k * 2 * math.pi / 24 for k in range(24)]]
        par = join([cone, fr] + drops + [sph('ParasolTop', 0.06, (0, -1.45, 3.26), 'gold')], 'Parasol', origin=Vector((0, -1.45, 3.0)))
        extras += [join(pp, 'ParasolStand'), par]
        for i, (sx, c) in enumerate([(-1.35, 'blue'), (1.35, 'red')]):
            pole = cyl('FlagPole', 0.025, (sx, 0.6, 0.0), (sx, 0.6, 2.9), 'wood', 12); cap = sph('FlagCap', 0.06, (sx, 0.6, 2.95), 'gold')
            bm = bmesh.new(); nseg = 16; vs = []
            for j in range(nseg + 1):
                yy = 0.6 + (0.9 if sx > 0 else 0.9) * 0 + j * 0.8 / nseg * (1)
                vs.append((bm.verts.new((sx, 0.6 + j * 0.05, 2.85)), bm.verts.new((sx, 0.6 + j * 0.05, 2.25))))
            for j in range(nseg): bm.faces.new((vs[j][0], vs[j + 1][0], vs[j + 1][1], vs[j][1]))
            me = bpy.data.meshes.new('Flag'); bm.to_mesh(me); fo = bpy.data.objects.new(f'Flag{i}', me); CUR[0].objects.link(fo); put(fo, c, False)
            md = fo.modifiers.new('s', 'SOLIDIFY'); md.thickness = 0.01; bpy.context.view_layer.objects.active = fo; bpy.ops.object.select_all(action='DESELECT'); fo.select_set(True); bpy.ops.object.modifier_apply(modifier='s')
            bpy.context.scene.cursor.location = (sx, 0.6, 2.85); bpy.ops.object.origin_set(type='ORIGIN_CURSOR')
            extras += [join([pole, cap], f'FlagPole{i}'), fo]
    return [body_obj] + extras

def build_rickshaw():
    WR = 0.55; out = []
    for side, sx in (('L', -0.62), ('R', 0.62)):
        c = (sx, 0, WR); parts = [torus('Tire', WR, 0.035, c, 'iron'), torus('Rim', WR - 0.05, 0.03, c, 'wood')]
        for k in range(14):
            a = k * 2 * math.pi / 14
            parts.append(cyl('Spoke', 0.013, (sx, 0.07 * math.cos(a), WR + 0.07 * math.sin(a)), (sx, (WR - 0.07) * math.cos(a), WR + (WR - 0.07) * math.sin(a)), 'wood', 10))
        parts.append(cyl('Hub', 0.075, (sx - 0.07, 0, WR), (sx + 0.07, 0, WR), 'gold'))
        out.append(join(parts, 'Wheel' + side, origin=Vector(c)))
    p = [cyl('Axle', 0.025, (-0.66, 0, WR), (0.66, 0, WR), 'iron'), box('Seat', (1.0, 0.62, 0.34), (0, 0.05, 0.86), 'lac', 0.04),
         box('Cushion', (0.86, 0.48, 0.08), (0, 0.06, 1.06), 'cush', 0.03), box('Back', (0.92, 0.1, 0.5), (0, -0.22, 1.28), 'cush', 0.04),
         box('Foot', (0.78, 0.36, 0.04), (0, 0.55, 0.52), 'lac', 0.015), box('TrimFront', (1.04, 0.035, 0.035), (0, 0.36, 0.7), 'gold', 0.01),
         cyl('FootStrut', 0.018, (-0.32, 0.36, 0.7), (-0.32, 0.55, 0.53), 'gold'), cyl('FootStrut', 0.018, (0.32, 0.36, 0.7), (0.32, 0.55, 0.53), 'gold')]
    for y in (-0.26, 0.36): p.append(box('Trim', (1.04, 0.035, 0.04), (0, y, 1.04), 'gold', 0.01))
    for sx in (-0.46, 0.46):
        p += [cyl('Shaft', 0.032, (sx, 0.3, 0.84), (sx, 2.05, 0.66), 'wood'), cyl('Cap', 0.04, (sx, 2.05, 0.66), (sx, 2.12, 0.653), 'gold')]
    p.append(cyl('Crossbar', 0.026, (-0.46, 1.95, 0.67), (0.46, 1.95, 0.67), 'wood'))
    for sx in (-0.55, 0.55): p += [cyl('LampPost', 0.012, (sx, 0.36, 1.04), (sx, 0.36, 1.25), 'gold', 8), cyl('Lamp', 0.06, (sx, 0.36, 1.25), (sx, 0.36, 1.37), 'lamp', 16)]
    bm = bmesh.new(); ang = [math.radians(-10 + k * (125 / 16)) for k in range(17)]
    ring = [[bm.verts.new((x, -0.05 - 0.55 * math.cos(a), 0.98 + 0.55 * math.sin(a))) for a in ang] for x in (-0.53, 0.53)]
    for k in range(16): bm.faces.new((ring[0][k], ring[0][k + 1], ring[1][k + 1], ring[1][k]))
    me = bpy.data.meshes.new('Hood'); bm.to_mesh(me); h = bpy.data.objects.new('Hood', me); CUR[0].objects.link(h)
    bpy.context.view_layer.objects.active = h; bpy.ops.object.select_all(action='DESELECT'); h.select_set(True)
    md = h.modifiers.new('s', 'SOLIDIFY'); md.thickness = 0.03; bpy.ops.object.modifier_apply(modifier='s'); put(h, 'fabric'); p.append(h)
    for k in (4, 9, 14):
        a = ang[k]; p.append(cyl('Rib', 0.016, (-0.55, -0.055 - 0.55 * math.cos(a), 0.98 + 0.55 * math.sin(a)), (0.55, -0.055 - 0.55 * math.cos(a), 0.98 + 0.55 * math.sin(a)), 'gold', 10))
    out.insert(0, join(p, 'Body', origin=Vector((0, 0, 0))))
    return out

def build_ox_cart():
    # 소달구지: 짚단을 실은 나무 수레를 한우가 끈다. 수레 앞(+Y)에 소가 선다
    WR = 0.62; out = []
    for side, sx in (('L', -0.74), ('R', 0.74)):
        c = (sx, -0.1, WR); parts = [torus('Tire', WR, 0.05, c, 'dkwood'), torus('Rim', WR - 0.06, 0.04, c, 'wood')]
        for k in range(10):
            a = k * 2 * math.pi / 10
            parts.append(cyl('Spoke', 0.022, (sx, -0.1 + 0.09 * math.cos(a), WR + 0.09 * math.sin(a)), (sx, -0.1 + (WR - 0.08) * math.cos(a), WR + (WR - 0.08) * math.sin(a)), 'wood', 10))
        parts.append(cyl('Hub', 0.1, (sx - 0.09, -0.1, WR), (sx + 0.09, -0.1, WR), 'iron'))
        out.append(join(parts, 'Wheel' + side, origin=Vector(c)))
    p = [cyl('Axle', 0.035, (-0.8, -0.1, WR), (0.8, -0.1, WR), 'iron'),
         box('Bed', (1.24, 1.7, 0.09), (0, 0.0, 0.8), 'wood', 0.02)]
    # 낮은 난간과 말뚝
    for sx in (-0.6, 0.6):
        p.append(box('Rail', (0.06, 1.7, 0.16), (sx, 0.0, 0.93), 'dkwood', 0.015))
        for y in (-0.78, -0.26, 0.26, 0.78): p.append(cyl('Stake', 0.028, (sx, y, 0.84), (sx, y, 1.1), 'dkwood', 10))
    p.append(box('Tail', (1.24, 0.06, 0.16), (0, -0.84, 0.93), 'dkwood', 0.015))
    # 짚단
    for y, z in ((-0.5, 0.99), (0.0, 0.99), (0.5, 0.99), (-0.25, 1.27), (0.25, 1.27)):
        p.append(cyl('Straw', 0.15, (-0.5, y, z), (0.5, y, z), 'straw', 20))
        for x in (-0.28, 0.28): p.append(torus('Band', 0.155, 0.012, (x, y, z), 'dkwood'))
    # 채(수레를 소에 잇는 두 막대)와 멍에
    for sx in (-0.42, 0.42):
        p += [cyl('Shaft', 0.035, (sx, 0.85, 0.82), (sx * 0.86, 2.5, 1.08), 'wood'), sph('ShaftCap', 0.045, (sx * 0.86, 2.5, 1.08), 'iron')]
    p.append(cyl('Yoke', 0.04, (-0.46, 3.45, 1.42), (0.46, 3.45, 1.42), 'wood'))
    for sx in (-0.2, 0.2): p.append(cyl('YokePeg', 0.02, (sx, 3.45, 1.42), (sx, 3.45, 1.12), 'dkwood', 8))
    for sx in (-0.36, 0.36): p.append(cyl('Trace', 0.012, (sx * 1.15, 2.5, 1.08), (sx, 3.45, 1.4), 'dkwood', 8))
    # 앞 모서리의 초롱
    p += [cyl('LampPost', 0.016, (0.6, 0.86, 1.0), (0.6, 0.86, 1.42), 'dkwood', 8), cyl('Lamp', 0.075, (0.6, 0.86, 1.42), (0.6, 0.86, 1.58), 'lamp', 16)]
    cart = join(p, 'Body', origin=Vector((0, 0, 0)))
    out.insert(0, cart)
    out.append(join(ox_parts(3.05, 'hide'), 'Ox', origin=Vector((0, 3.05, 0))))
    return out

def ox_parts(cy, hide):
    # 한우 한 마리. 몸통 중심이 y=cy, 머리가 +Y
    d = cy - 3.05
    ox = [sph('OxBody', 0.5, (0, 3.05 + d, 1.08), hide, (0.82, 1.55, 0.84)),
          sph('OxHump', 0.26, (0, 3.5 + d, 1.45), hide, (1.1, 0.9, 0.6))]
    for sx in (-0.24, 0.24):
        for y in (2.5, 3.55):
            ox += [cyl('OxLeg', 0.1, (sx, y + d, 0.95), (sx, y + d, 0.14), hide, 12, 0.08), cyl('Hoof', 0.085, (sx, y + d, 0.14), (sx, y + d, 0.0), 'black', 12)]
    ox += [sph('OxNeck', 0.3, (0, 3.85 + d, 1.3), hide, (0.8, 1.0, 1.0)),
           sph('OxHead', 0.3, (0, 4.15 + d, 1.32), hide, (0.82, 1.15, 0.9)),
           sph('OxMuzzle', 0.19, (0, 4.45 + d, 1.2), 'muzzle', (1.1, 0.8, 0.8))]
    for sx in (-1, 1):
        ox += [cyl('OxHorn', 0.05, (sx * 0.2, 4.05 + d, 1.5), (sx * 0.42, 4.0 + d, 1.72), 'horn', 12, 0.012),
               sph('OxEar', 0.1, (sx * 0.3, 4.0 + d, 1.4), hide, (1.3, 0.5, 0.6)),
               sph('OxEye', 0.035, (sx * 0.19, 4.33 + d, 1.42), 'black')]
    ox += [cyl('OxTail', 0.025, (0, 2.32 + d, 1.3), (0, 2.18 + d, 0.7), hide, 8), sph('OxTuft', 0.06, (0, 2.17 + d, 0.66), 'muzzle', (1, 1, 1.5))]
    return ox

def export(name, out_dir):
    bpy.ops.object.select_all(action='DESELECT')
    for o in CUR[0].objects: o.select_set(True)
    bpy.ops.export_scene.gltf(filepath=os.path.abspath(os.path.join(out_dir, f'{name}.glb')), export_format='GLB', use_selection=True, export_apply=True)
    print('exported', name, [o.name for o in CUR[0].objects if not o.name.startswith(('Spoke',))][:12], flush=True)

import argparse, os, sys

def main():
    argv = sys.argv[1:]
    names = argv[argv.index('--') + 1:] if '--' in argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='assets3d/models')
    args, _ = ap.parse_known_args(argv[:argv.index('--')] if '--' in argv else argv)
    which = names or ['ox_cart', 'rickshaw', 'gama', 'kkotgama', 'eoga']
    os.makedirs(args.out, exist_ok=True)
    for name in which:
        CUR[0] = coll(name)
        if name == 'ox_cart': build_ox_cart()
        elif name == 'rickshaw': build_rickshaw()
        else: build_gama({'gama': 'gama', 'kkotgama': 'kkot', 'eoga': 'eoga'}[name])
        export(name, args.out)

# region_set.py처럼 도우미만 가져갈 때는 내보내지 않는다
if __name__ == '__main__':
    main()
