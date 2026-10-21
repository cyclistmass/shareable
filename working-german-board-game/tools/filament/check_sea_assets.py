#!/usr/bin/env python3
"""Validate the exported GLBs against the runtime sea-axis and material contracts."""
import argparse,hashlib,json,pathlib,struct
from check_visibility import triangles

def main():
    p=argparse.ArgumentParser();p.add_argument('--assets',type=pathlib.Path,required=True)
    p.add_argument('--manifests',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
    rows=[]
    for manifest in sorted(a.manifests.glob('*.json')):
        record=json.loads(manifest.read_text());name=record['asset'];path=a.assets/(name+'.glb');data=path.read_bytes()
        assert hashlib.sha256(data).hexdigest()==record['sha256'],name+' hash'
        points=triangles(path);lo=points.min(axis=(0,1));hi=points.max(axis=(0,1))
        if name.startswith('coast_'):
            assert hi[1]<0,name+' must stay below plates'
            assert hi[2]>.16 and lo[2]>-.04,name+' sea must face local +Z'
            assert lo[1]<=-.24,name+' must reach water'
        if name=='harbour_pier':
            assert lo[2]<.045 and hi[2]>.71,name+' must run from anchor to local +Z .72'
            assert hi[2]<.73,name+' endpoint'
        if name.startswith('sea_'):
            length=struct.unpack_from('<I',data,12)[0];gltf=json.loads(data[20:20+length])
            assert all(m.get('alphaMode')=='BLEND' for m in gltf['materials']),name+' fade'
        rows.append(dict(asset=name,triangles=len(points),bytes=len(data),min=lo.tolist(),max=hi.tolist()))
    a.output.write_text(json.dumps(dict(passed=len(rows),assets=rows),indent=2));print(f'{len(rows)} exported sea assets passed bounds, axes, hashes and blending checks')

if __name__=='__main__':main()
