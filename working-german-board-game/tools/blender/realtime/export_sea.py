"""Export the existing authored water props and two coast options for review.

Blender -b --python-exit-code 1 -P export_sea.py -- --source DIR --out DIR
Uses export_assets' bounded vertex-colour export; original sources stay read-only.
"""
import argparse, math, sys
from pathlib import Path
import bpy
from mathutils import Vector

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import export_assets as rt

def material(name, color, metallic=0):
    m = bpy.data.materials.new(name); m.use_nodes = True
    p = m.node_tree.nodes.get('Principled BSDF')
    p.inputs['Base Color'].default_value = (*color, 1)
    p.inputs['Roughness'].default_value = .76
    p.inputs['Metallic'].default_value = metallic
    return m

def box(name, location, size, mat):
    bpy.ops.mesh.primitive_cube_add(size=1, location=location)
    o = bpy.context.object; o.name=name; o.scale=size; o.data.materials.append(mat)
    return o

def cliff(option):
    rock = [material('weathered sandstone'+str(i), c) for i,c in enumerate([
        (.25,.22,.18),(.37,.32,.24),(.46,.40,.29),(.30,.30,.24)])]
    grass = material('low moss grass lip',(.19,.25,.065))
    # Bake our own mottled stone and moss, with the weathered palette of cliff_top_*.
    for i,m in enumerate(rock+[grass]):
        tree=m.node_tree;noise=tree.nodes.new('ShaderNodeTexNoise');noise.inputs['Scale'].default_value=28
        noise.inputs['Detail'].default_value=3; ramp=tree.nodes.new('ShaderNodeValToRGB')
        base=tree.nodes['Principled BSDF'].inputs['Base Color'].default_value[:3]
        ramp.color_ramp.elements[0].color=tuple(v*.58 for v in base)+(1,)
        ramp.color_ramp.elements[1].color=tuple(min(1,v*1.27) for v in base)+(1,)
        tree.links.new(noise.outputs['Fac'],ramp.inputs['Fac']);tree.links.new(ramp.outputs['Color'],tree.nodes['Principled BSDF'].inputs['Base Color'])
    objects = []
    width = .17 if option == 'a' else .26
    verts=[];faces=[];indices=[]
    # Three weathered strata, alternating narrow ledges and undercuts. The jagged
    # mesh stays continuous at its ends and never rises above the tile plane.
    for row,z in enumerate([-.005,-.075,-.155,-.245]):
        for i in range(17):
            x=-.64+i*1.28/16
            y=width + .035*math.sin(i*2.7+row*1.4)
            verts.append((x,y,z-.013*math.sin(i*3.1)**2 if row==0 else z))
    for row in range(3):
        for i in range(16):
            k=row*17+i
            faces.extend([(k,k+17,k+1),(k+1,k+17,k+18)])
            indices.extend([(i+row)%4,(i+row+1)%4])
    for i in range(17):verts.append((-.64+i*1.28/16,-.028,-.008))
    for i in range(16):
        faces.append((i,i+1,69+i,68+i));indices.append(1 if i%3!=1 else 2)
    # Close the inland side and segment ends; adjacent sections overlap at corners.
    faces.extend([(68,84,67,51),(0,68,51,34,17),(16,33,50,67,84)])
    indices.extend([0,1,1])
    mesh=bpy.data.meshes.new('stratified cliff');mesh.from_pydata(verts,[],[tuple(reversed(f)) for f in faces]);mesh.update()
    o=bpy.data.objects.new('eroded coast',mesh);bpy.context.collection.objects.link(o)
    for m in rock+[grass]:mesh.materials.append(m)
    for polygon,index in zip(mesh.polygons,indices):polygon.material_index=index
    objects.append(o)
    objects.append(box('thin living grass lip',(0,.004,-.013),(1.28,.064,.016),grass))
    o=rt.merge(objects,'coast_'+option)
    # Blender +Y exports as glTF -Z; the runtime coast contract points out along +Z.
    o.rotation_euler.z=math.pi;rt.select([o]);bpy.ops.object.transform_apply(location=True,rotation=True,scale=True)
    rt.bake_colour(o,'CoastStoneGrass')
    return [o],dict(own=True,top=-.005,waterline=-.24,width=width)

def pier():
    wood=material('dark salt weathered wood',(.16,.09,.045))
    brass=material('aged rubbed brass',(.40,.28,.12),.7)
    objects=[]
    for i in range(9):
        objects.append(box('deck plank',(0,i*.085,-.06),(.17,.078,.038),wood))
    for y in [.08,.36,.66]:
        for x in [-.068,.068]:
            objects.append(box('pile',(x,y,-.155),(.038,.038,.25),wood))
            objects.append(box('worn brass pile cap',(x,y,-.025),(.044,.044,.012),brass))
    o=rt.merge(objects,'harbour_pier')
    o.rotation_euler.z=math.pi;rt.select([o]);bpy.ops.object.transform_apply(location=True,rotation=True,scale=True)
    rt.bake_colour(o,'PierWoodBrass')
    return [o],dict(own=True,length=.72,waterline=-.24)

def prop(sp, name, phase=0):
    scene,cam,root=sp.reset_scene(256)
    if name=='sloop': sp.build_sloop(root)
    elif name=='raiders': sp.build_raiders(root)
    else:
        wings=sp.build_gull(root);sp.pose_gull(wings,phase)
    objects=[o for o in scene.objects if o.type=='MESH' and o.get('layer')!='wake']
    rt.apply_source_modifiers(objects)
    bpy.context.view_layer.update()
    # Detach with the evaluated world transform, then merge at the waterline anchor.
    for o in objects:
        world=o.matrix_world.copy();o.parent=None;o.matrix_world=world
    groups={}
    for o in objects:
        key='SailCloth' if name=='sloop' and o.get('layer')=='body' else 'SeaPropDetails'
        groups.setdefault(key,[]).append(o)
    result=[]
    for key,items in groups.items():
        o=rt.merge(items,key);rt.reduce(o,1100 if key=='SeaPropDetails' else 500)
        rt.bake_colour(o,key);result.append(o)
    return result,dict(source='existing own sea_props.py',phase=phase,waterline=0)

def ports(source, out):
    # Import only the model definitions; suppress the legacy rendering CLI.
    code=(source/'ports.py').read_text().split('# ---------------------------------------------------------------- run')[0]
    previous=sys.argv;sys.argv=['ports.py','--','--out',str(out)]
    namespace={'__file__':str(source/'ports.py'),'__name__':'port_models'}
    exec(compile(code,str(source/'ports.py'),'exec'),namespace);sys.argv=previous
    sp=namespace['sp']
    for kind in ['3to1','timber','clay','fleece','grain','stone']:
        scene,cam,root=sp.reset_scene(256)
        mats=namespace['materials']();mount=namespace['shape_buoy'](root,mats)
        mount.scale=tuple(v*1.25 for v in mount.scale)
        namespace['EMBLEMS']['port_'+kind](mount,mats)
        objects=[o for o in scene.objects if o.type=='MESH']
        rt.apply_source_modifiers(objects);bpy.context.view_layer.update()
        for o in objects:
            world=o.matrix_world.copy();o.parent=None;o.matrix_world=world
        o=rt.merge(objects,'port_'+kind);rt.reduce(o,1300);rt.bake_colour(o,'PortAgedBrassWood')
        rt.export([o],'harbour_'+kind,dict(source='existing own ports.py buoy',kind=kind))

def water_marks():
    for name in ['sea_wavelet','sea_wake','sea_shadow']:
        bpy.ops.wm.read_factory_settings(use_empty=True)
        verts=[];faces=[]
        if name=='sea_wavelet':
            for i in range(13):
                x=-.5+i/12
                y=.13*(1-4*x*x)
                thickness=.06*math.sin(math.pi*i/12)
                verts.extend([(x,y,0),(x,y+thickness,0)])
            faces=[(2*i,2*i+1,2*i+3,2*i+2) for i in range(12)]
        else:
            ry=.055 if name=='sea_wake' else .075
            rx=.12 if name=='sea_wake' else .22
            verts=[(rx*math.cos(i*math.tau/24),ry*math.sin(i*math.tau/24),0) for i in range(24)]
            faces=[tuple(range(24))]
        mesh=bpy.data.meshes.new(name);mesh.from_pydata(verts,[],faces);mesh.update()
        o=bpy.data.objects.new(name,mesh);bpy.context.collection.objects.link(o)
        m=material(name,(.8,.94,1));p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Alpha'].default_value=.5
        mesh.materials.append(m)
        rt.export([o],name,dict(own=True,waterline=0))
        # Explicit glTF blending, independent of Blender's version-specific alpha UI.
        import json,struct
        path=rt.OUT/'glb'/(name+'.glb');data=path.read_bytes();length=struct.unpack_from('<I',data,12)[0]
        gltf=json.loads(data[20:20+length]);gltf['materials'][0]['alphaMode']='BLEND';gltf['materials'][0]['doubleSided']=True
        gltf['materials'][0]['extensions']={'KHR_materials_unlit':{}}
        gltf.setdefault('extensionsUsed',[]).append('KHR_materials_unlit')
        js=json.dumps(gltf,separators=(',',':')).encode();js+=b' '*(-len(js)%4)
        binary=data[20+length:]
        path.write_bytes(struct.pack('<5I',0x46546c67,2,20+len(js)+len(binary),len(js),0x4e4f534a)+js+binary)
        import hashlib
        manifest=rt.OUT/'manifests'/(name+'.json');record=json.loads(manifest.read_text())
        record.update(bytes=path.stat().st_size,sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        manifest.write_text(json.dumps(record,indent=2))

def preview(objects,path):
    scene=bpy.context.scene
    scene.render.engine='CYCLES';scene.cycles.samples=24
    prefs=bpy.context.preferences.addons['cycles'].preferences
    prefs.compute_device_type='OPTIX';prefs.get_devices()
    for device in prefs.devices: device.use=device.type!='CPU'
    scene.cycles.device='GPU';scene.render.film_transparent=False
    scene.world=bpy.data.worlds.new('preview sea');scene.world.use_nodes=True
    scene.world.node_tree.nodes['Background'].inputs[0].default_value=(.035,.15,.23,1)
    scene.world.node_tree.nodes['Background'].inputs[1].default_value=.7
    bpy.ops.object.light_add(type='AREA',location=(-3,-4,6));bpy.context.object.data.energy=650
    bpy.context.object.data.shape='DISK';bpy.context.object.data.size=5
    bpy.ops.object.camera_add(location=(1,-2.8,2.6));cam=bpy.context.object
    cam.rotation_euler=(Vector((0,0,-.08))-cam.location).to_track_quat('-Z','Y').to_euler()
    cam.data.type='ORTHO';cam.data.ortho_scale=1.55;scene.camera=cam
    scene.render.resolution_x=640;scene.render.resolution_y=400;scene.render.resolution_percentage=100
    scene.view_settings.view_transform='Standard';scene.render.filepath=str(path)
    bpy.ops.render.render(write_still=True)

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--source',type=Path,required=True);ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--review-only',action='store_true');a=ap.parse_args(sys.argv[sys.argv.index('--')+1:])
    a.out=a.out.resolve();a.source=a.source.resolve()
    a.out.mkdir(parents=True,exist_ok=True);rt.OUT=a.out
    for option in ['a','b']:
        bpy.ops.wm.read_factory_settings(use_empty=True)
        objects,meta=cliff(option);rt.export(objects,'coast_'+option,meta)
        preview(objects,a.out/('coast_'+option+'.png'))
    if a.review_only:return
    bpy.ops.wm.read_factory_settings(use_empty=True);objects,meta=pier();rt.export(objects,'harbour_pier',meta)
    sys.path.insert(0,str(a.source));previous=sys.argv;sys.argv=[sys.argv[0]]
    import sea_props as sp
    sys.argv=previous
    for name in ['sloop','raiders']:
        objects,meta=prop(sp,name);rt.export(objects,'live_'+name,meta)
    for frame in range(16):
        objects,meta=prop(sp,'gull',frame/16);rt.export(objects,'live_gull_'+str(frame),meta)
    ports(a.source,a.out)
    water_marks()
    bpy.ops.wm.read_factory_settings(use_empty=True)
    # Dense playing area plus a cheap far rim. Gerstner deformation is bounded by .06 hex.
    verts=[];faces=[];n=128;extent=16
    for y in range(n+1):
        for x in range(n+1):verts.append((-extent+2*extent*x/n,-extent+2*extent*y/n,-.24))
    for y in range(n):
        for x in range(n):
            k=y*(n+1)+x;faces.append((k,k+1,k+n+2,k+n+1))
    k=len(verts)
    verts.extend([(-64,-64,-.24),(64,-64,-.24),(64,64,-.24),(-64,64,-.24)])
    for i,(a,b) in enumerate([(0,n),(n,(n+1)**2-1),((n+1)**2-1,n*(n+1)),(n*(n+1),0)]):
        faces.append((a,b,k+(i+1)%4,k+i))
    mesh=bpy.data.meshes.new('mobile water grid');mesh.from_pydata(verts,[],faces);mesh.update()
    o=bpy.data.objects.new('live_water_mesh',mesh);bpy.context.collection.objects.link(o)
    mesh.materials.append(material('placeholder replaced by live_water.filamat',(.02,.15,.25)))
    rt.export([o],'live_water_mesh',dict(own=True,grid=128,near_extent=16,far_extent=64,max_displacement=.06))

if __name__=='__main__':main()
