"""Blender 4.5: own V3/source scenes -> bounded, vertex-colour glTF 2.0.

Run one asset per process. No original file is saved or modified.
"""
import argparse, hashlib, inspect, json, math, os, sys, time
from pathlib import Path
import bpy, bmesh
from mathutils import Matrix

HERE = Path(__file__).resolve().parent
SRC = HERE.parent
ART = Path(os.environ.get('WGBG_ART_ROOT', str(HERE.parent.parent.parent.parent / 'art')))
OUT = ART / 'realtime'
sys.path.insert(0, str(SRC))

def log(*args): print('REALTIME', *args, flush=True)

def triangles(o):
    o.data.calc_loop_triangles()
    return len(o.data.loop_triangles)

def select(objects):
    bpy.ops.object.select_all(action='DESELECT')
    for o in objects: o.select_set(True)
    bpy.context.view_layer.objects.active = objects[0]

def merge(objects, name):
    select(objects)
    if len(objects)>1: bpy.ops.object.join()
    o=bpy.context.object; o.name=name
    bpy.context.scene.cursor.location=(0,0,0)
    bpy.ops.object.origin_set(type='ORIGIN_CURSOR')
    bpy.ops.object.transform_apply(location=True,rotation=True,scale=True)
    return o

def reduce(o, budget):
    select([o])
    # Dissolve near-coplanar subdivisions first, then collapse to a hard budget.
    n=triangles(o)
    if n>budget:
        d=o.modifiers.new('Mobile triangle budget','DECIMATE')
        d.ratio=min(.999, budget/n*.985)
        d.use_collapse_triangulate=True
        bpy.ops.object.modifier_apply(modifier=d.name)
    for _ in range(4):
        n=triangles(o)
        if n<=budget: break
        d=o.modifiers.new('Hard cap','DECIMATE'); d.ratio=budget/n*.97
        d.use_collapse_triangulate=True
        bpy.ops.object.modifier_apply(modifier=d.name)
    if triangles(o)>budget:
        # Disconnected leaves/fibres cannot collapse below one triangle each.
        # Drop the smallest complete islands, rather than leave severed fragments.
        bm=bmesh.new();bm.from_mesh(o.data)
        unseen=set(bm.verts); islands=[]
        while unseen:
            seed=unseen.pop();verts={seed};stack=[seed];faces=set()
            while stack:
                v=stack.pop();faces.update(v.link_faces)
                for edge in v.link_edges:
                    w=edge.other_vert(v)
                    if w in unseen: unseen.remove(w);verts.add(w);stack.append(w)
            islands.append((sum(f.calc_area() for f in faces),sum(len(f.verts)-2 for f in faces),verts))
        remaining=triangles(o);removed=[]
        for area,count,verts in sorted(islands,key=lambda x:x[0]):
            if remaining<=budget: break
            if count>=remaining: continue
            removed.extend(verts);remaining-=count
        bmesh.ops.delete(bm,geom=removed,context='VERTS');bm.to_mesh(o.data);bm.free()
    o.data.validate();o.data.update()
    assert triangles(o)<=budget, (o.name,triangles(o),budget)
    log('REDUCED',o.name,n,'->',triangles(o))

def bake_colour(o, label, metallic=0, rough=.85):
    """Evaluate the actual authored shader's Base Color, bake EMIT to corners.

    No illumination baked into PBR albedo. Node graphs are discarded after baking.
    High-frequency bump is intentionally omitted at mobile vertex resolution.
    """
    select([o])
    attr=o.data.color_attributes.new(name='COLOR_0',type='FLOAT_COLOR',domain='CORNER')
    o.data.color_attributes.active_color=attr
    backups=[]
    for mat in o.data.materials:
        if not mat: continue
        mat.use_nodes=True; nodes=mat.node_tree.nodes; links=mat.node_tree.links
        p=next((n for n in nodes if n.type=='BSDF_PRINCIPLED'),None)
        output=next((n for n in nodes if n.type=='OUTPUT_MATERIAL' and n.is_active_output),None)
        if output is None: output=nodes.new('ShaderNodeOutputMaterial')
        old=[(l.from_socket,l.to_socket) for l in links if l.to_node==output]
        for l in list(links):
            if l.to_node==output: links.remove(l)
        emission=nodes.new('ShaderNodeEmission')
        if p:
            source=p.inputs['Base Color']
            if source.is_linked: links.new(source.links[0].from_socket,emission.inputs['Color'])
            else: emission.inputs['Color'].default_value=source.default_value
        else: emission.inputs['Color'].default_value=mat.diffuse_color
        links.new(emission.outputs[0],output.inputs['Surface'])
        backups.append((mat,emission,old))
    scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.device='CPU'
    scene.cycles.samples=1; scene.render.bake.target='VERTEX_COLORS'
    scene.render.bake.use_selected_to_active=False
    log('BAKE',label,triangles(o))
    bpy.ops.object.bake(type='EMIT')
    for mat,emission,old in backups:
        mat.node_tree.nodes.remove(emission)
        for a,b in old: mat.node_tree.links.new(a,b)
    m=bpy.data.materials.new(label);m.use_nodes=True
    p=m.node_tree.nodes.get('Principled BSDF')
    p.inputs['Base Color'].default_value=(1,1,1,1)
    p.inputs['Metallic'].default_value=metallic;p.inputs['Roughness'].default_value=rough
    vc=m.node_tree.nodes.new('ShaderNodeVertexColor');vc.layer_name='COLOR_0'
    m.node_tree.links.new(vc.outputs['Color'],p.inputs['Base Color'])
    o.data.materials.clear();o.data.materials.append(m)
    for poly in o.data.polygons: poly.material_index=0
    return o

def apply_source_modifiers(objects):
    for o in objects:
        select([o])
        for mod in list(o.modifiers):
            if mod.type=='BEVEL': mod.segments=1
            try: bpy.ops.object.modifier_apply(modifier=mod.name)
            except RuntimeError: o.modifiers.remove(mod)

def tile(name):
    import render_v3 as v, v3_art, v3_finish, v3_story
    v3_art.install(v);v.ACTIVE_TERRAIN=name
    v.token.CX=.36;v.token.CY=-.30
    original_sheep=v.v2.sheep
    safe_flock=iter([(.20,.23),(.35,.57),(-.20,.65),(-.64,.35),(.08,.60)])
    def safe_sheep(x,y,heading=0,phase=0):
        before=set(bpy.context.scene.objects)
        original_sheep(x,y,heading,phase)
        group=set(bpy.context.scene.objects)-before
        bpy.context.view_layer.update()
        if all(v.token.allowed(*(o.matrix_world@p.co),.025) for o in group for p in o.data.vertices):return
        from mathutils import Vector
        for nx,ny in safe_flock:
            shift=Vector((nx-x,ny-y,v.terrain_z(v.meadow,nx,ny)-v.terrain_z(v.meadow,x,y)))
            for o in group:o.location+=shift
            bpy.context.view_layer.update()
            if all(v.token.allowed(*(o.matrix_world@p.co),.025) for o in group for p in o.data.vertices):return
            for o in group:o.location-=shift
        raise RuntimeError('No safe whole-sheep placement for lower-right clearing')
    v.v2.sheep=safe_sheep
    original_goat=v.density.goat
    def safe_goat(x,y,heading=0,phase=0,z=None):
        if y>.3:
            x,y=-.65,.10;z=v.meadow(x,y)
        original_goat(x,y,heading,phase,z)
    v.density.goat=safe_goat
    def mobile_pine(x,y,h,style,rng,mats):
        # Same V3 locations/heights/palette, but broad closed branch whorls.
        # Needle islands collapse poorly; preserve the crown silhouette instead.
        bark,greens=mats
        v.cone('V3 mobile pine trunk',(x,y,0),h*.024,h*.92,bark,8,top=h*.010)
        vs=[];fs=[];indices=[]
        for level in range(6):
            t=.12+level*.125;r=h*.34*(1-t)**.85;z=h*t
            offset=len(vs);phase=level*.72
            for j in range(8):
                a=j*math.tau/8+phase;rr=r*rng.uniform(.87,1.10)
                vs.append((x+rr*math.cos(a),y+rr*math.sin(a),z+h*rng.uniform(-.012,.012)))
            vs.append((x,y,min(h,z+h*.25)))
            for j in range(8): fs.append((offset+j,offset+(j+1)%8,offset+8));indices.append((j+level)%len(greens))
            fs.append(tuple(offset+j for j in reversed(range(8))));indices.append(0)
        o=v.mesh('V3 mobile layered pine canopy',vs,fs,greens[0])
        for m in greens[1:]:o.data.materials.append(m)
        for f,idx in zip(o.data.polygons,indices):f.material_index=idx
    v.tree=mobile_pine
    scene,cam=v.setup();v.density.alpine_height_v3=lambda x,y:0
    meta=getattr(v,name)();v3_art.enhance(name)
    meta['finish']=v3_finish.enrich(name,v);meta['story']=v3_story.enrich(name,v)
    v.token.prepare_scene(name);v3_art.ground_inlay(name);v3_story.apron(name,v)
    v.rich_materials(scene);v3_art.materials(scene,name);v3_finish.materials(scene,name)
    v.validate_geometry(scene)
    # Source placement/height guards already use the requested lower-right circle.
    objects=[o for o in scene.objects if o.type=='MESH']
    volumes=[o for o in objects if any(m and m.use_nodes and any(n.bl_idname=='ShaderNodeVolumePrincipled' for n in m.node_tree.nodes) for m in o.data.materials)]
    for o in volumes: bpy.data.objects.remove(o,do_unlink=True)
    objects=[o for o in objects if o not in volumes]
    apply_source_modifiers(objects)
    groups={k:[] for k in ['base','surface','story','canopy','groundcover','clearing']}
    for o in objects:
        select([o]);bpy.ops.object.transform_apply(location=True,rotation=True,scale=True)
        bm=bmesh.new();bm.from_mesh(o.data)
        if o.name.startswith('thick hex'):
            for vert in bm.verts: vert.co.x/=.93;vert.co.y/=.93
        bm.to_mesh(o.data);bm.free()
        low=o.name.lower()
        if 'flat number clearing' in low: key='clearing'
        elif low.startswith('thick hex'): key='base'
        elif low.startswith(('lush pasture hills','rolling field ground','deep excavated','solid dunes','r3 seven','soft curved wheat canopy')): key='surface'
        elif any(w in low for w in ['pine','branch fan','conifer','needles']): key='canopy'
        elif any(w in low for w in ['flower','leaf','leaves','grass','groundcover','petal','rubble','pebble','clover','inlay','apron','fern','pollen','scatter','loose straw']): key='groundcover'
        else: key='story'
        groups[key].append(o)
    limits={'base':100,'surface':1800,'story':2600,'canopy':2800,'groundcover':600,'clearing':64}
    result=[]
    for key,os in groups.items():
        if not os: continue
        log('SOURCE_GROUP',key,len(os),sum(triangles(o) for o in os))
        o=merge(os,key);reduce(o,limits[key]);bake_colour(o,'Terrain_PBR');result.append(o)
    # One exported material/primitive for the complete terrain, including all props.
    shared=result[0].data.materials[0]
    for o in result: o.data.materials[0]=shared
    o=merge(result,'tile_'+name+'_LOD0')
    o.data.validate();o.data.update()
    meta.update(clearing_blender=[.36,-.30],clearing_radius=.31,lower_right_guard=True,volumes_omitted=len(volumes))
    return [o],meta

def building(theme,name):
    path=(ART/'buildings/default'/f'{name}.blend') if theme=='default' else ART/'buildings-pirate-astra'/f'pirate_{name}.blend'
    bpy.ops.wm.open_mainfile(filepath=str(path))
    objects=[o for o in bpy.context.scene.objects if o.type=='MESH' and 'layer' in o]
    assert objects
    for o in objects:
        key='original_material' if 'original_material' in o else 'original'
        if key in o: o.data.materials[0]=bpy.data.materials[o[key]]
    apply_source_modifiers(objects)
    groups={'WoodBody':[],'BrassTrim':[],'FixedDetails':[]}
    for o in objects:
        mat=o.data.materials[0]
        key='WoodBody' if o['layer']=='body' else ('BrassTrim' if 'brass' in mat.name.lower() else 'FixedDetails')
        groups[key].append(o)
    quotas={'WoodBody':650,'BrassTrim':500,'FixedDetails':300}
    result=[]
    for key,os in groups.items():
        if not os: continue
        o=merge(os,key);reduce(o,quotas[key]);bake_colour(o,key,.74 if key=='BrassTrim' else 0,.32 if key=='BrassTrim' else .6)
        o['tintable']=key=='WoodBody';result.append(o)
    return result,{'source':str(path),'source_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'pivot':'original base anchor (0,0,0)'}

def bandit():
    sys.path.insert(0,str(ART/'bandit-v2'))
    import render_bandit_final as final
    original=final.original;original.setup()
    original.woodmat=original.mat('dark carved wood plinth',(.065,.028,.014),0)
    original.wood(original.woodmat,(.065,.028,.014))
    source=inspect.getsource(original.model).replace('def model(finish):','def final_model(finish):',1)
    source=source.replace('return .24*max(0,-math.sin(a))**2*(r/.46)**2','return .12*max(0,-math.sin(a))**2*(r/.46)**2',1)
    ns=dict(original.__dict__);exec(compile(source,'final_model','exec'),ns)
    meta=ns['final_model']('B');final.aged_ivory(bpy.data.materials['ivory skull insert'])
    objects=[o for o in bpy.context.scene.objects if o.type=='MESH']
    apply_source_modifiers(objects);o=merge(objects,'Bandit');reduce(o,3900);bake_colour(o,'Bandit_Painted_AgedIvory',0,.58)
    return [o],meta

def simple(name):
    bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
    if name=='sea':
        bpy.ops.mesh.primitive_plane_add(size=22,location=(0,0,-.24));c=(.022,.19,.24);rough=.38
    else:
        bpy.ops.mesh.primitive_cylinder_add(vertices=48,radius=.27,depth=.024,location=(0,0,.012));c=(.82,.71,.47);rough=.65
    o=bpy.context.object;o.name='Sea' if name=='sea' else 'NumberPlate'
    bpy.context.scene.cursor.location=(0,0,0);bpy.ops.object.origin_set(type='ORIGIN_CURSOR')
    m=bpy.data.materials.new(o.name+'_PBR');m.use_nodes=True
    p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Base Color'].default_value=(*c,1);p.inputs['Roughness'].default_value=rough
    o.data.materials.append(m)
    return [o],{'pivot':'centre' if name=='sea' else 'plate bottom centre','radius':.27 if name=='number_plate' else None}

def export(objects,key,meta,lod=0):
    select(objects);folder=OUT/'glb';folder.mkdir(parents=True,exist_ok=True)
    path=folder/f'{key}.glb'
    bpy.ops.export_scene.gltf(filepath=str(path),export_format='GLB',use_selection=True,export_yup=True,
        export_texcoords=False,export_normals=True,export_attributes=False,
        export_cameras=False,export_lights=False,export_extras=True,export_materials='EXPORT')
    record={'asset':key,'triangles':sum(triangles(o) for o in objects),'bytes':path.stat().st_size,
            'meshes':len(objects),'draw_calls':sum(len(o.data.materials) for o in objects),'lod':lod,
            'bounds_blender':[[min((o.matrix_world@v.co)[k] for o in objects for v in o.data.vertices) for k in range(3)],
                              [max((o.matrix_world@v.co)[k] for o in objects for v in o.data.vertices) for k in range(3)]],
            'metadata':meta,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
    (OUT/'manifests').mkdir(exist_ok=True);(OUT/'manifests'/f'{key}.json').write_text(json.dumps(record,indent=2))
    log('EXPORTED',key,record['triangles'],record['bytes'])

def main():
    p=argparse.ArgumentParser();p.add_argument('--asset',required=True);args=p.parse_args(sys.argv[sys.argv.index('--')+1:]);key=args.asset
    start=time.monotonic()
    if key.startswith('tile_'):
        os,meta=tile(key[5:]);export(os,key+'_lod0',meta)
        reduce(os[0],max(64,triangles(os[0])//4));os[0].name=key+'_LOD1';export(os,key+'_lod1',meta,1)
    elif key=='bandit': os,meta=bandit();export(os,key,meta)
    elif key in ['sea','number_plate']: os,meta=simple(key);export(os,key,meta)
    else:
        theme,name=key.split('_',1);os,meta=building(theme,name);export(os,key,meta)
    log('DONE',key,round(time.monotonic()-start,2),'seconds')

if __name__=='__main__': main()
