"""Import the approved Space delivery, never generate artwork (#88).

Usage: python tools/import_space.py <space-theme directory>
Uses the same Lanczos/WebP pipeline as import_tiles3d.py. All 18 authored tilts
are retained; frame widths/contact heights are data, not inferred from pixels.
Incremental conversion, bounded workers and progress avoid silent long imports.
Only this importer's space_ outputs are written. Source files are read-only.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path
import shutil
import struct
from PIL import Image
from art_import import save_webp

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main"
RES = MAIN / "res/drawable-nodpi"
ASSETS = MAIN / "assets"
KT = MAIN / "java/com/mauro/wgbg/SpaceArtData.kt"
TERRAINS = dict(zip(["timber", "clay", "fleece", "grain", "stone", "desert"],
                    ["glass_bamboo_grove", "regolith_kilns", "algae_vats", "mushroom_farm", "asteroid_valley", "dead_crater_field"]))
CARD_RESOURCES = ["glass_bamboo_fibre", "regolith_bricks", "o2_canister", "mushrooms", "nickel_iron", "data_slate", "nano_cloth", "platinum_coin"]


def role(asset):
    for terrain, source in TERRAINS.items():
        if asset == source:
            return "tile_" + terrain
    for terrain, source in zip(list(TERRAINS)[:5], CARD_RESOURCES):
        if asset == "dock_" + source:
            return "port_" + terrain
    aliases = {"exchange_beacon": "merchant", "rift_corsair": "raiders_ship", "cliff_edge": "cliff",
               "starfield": "sea", "space_knight_l1": "knight_l1_active", "space_knight_l2": "knight_l2_active",
               "space_knight_l3": "knight_l3_active"}
    if asset.startswith("shield_pylon_"):
        return "ramparts_h" + asset[-1]
    return aliases.get(asset, asset.removeprefix("space_"))


def glb_stats(path):
    data = path.read_bytes()
    magic, version, length = struct.unpack_from("<III", data)
    assert (magic, version, length) == (0x46546C67, 2, len(data)), path
    size, kind = struct.unpack_from("<II", data, 12)
    assert kind == 0x4E4F534A
    gltf = json.loads(data[20:20 + size])
    primitives = [p for m in gltf.get("meshes", []) for p in m["primitives"]]
    assert all(p.get("mode", 4) == 4 for p in primitives)
    triangles = sum(gltf["accessors"][p["indices"]]["count"] // 3 for p in primitives)
    return {"triangles": triangles, "primitives": len(primitives), "textured": bool(gltf.get("textures")),
            "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


# The dome glass of towns and Capitals is authored teal inside FixedDetails, so it never took the owner colour in 3D
# (Space playtest, #88). Grey it like the wood body; liveTint paints FixedDetails of these models in the player colour.
OWNER_GLASS = ("town", "capital_academy", "capital_crown", "capital_guilds")
GLASS = (0.27, 0.68, 0.62)
BODY_GREY = 0.72


def glass_to_body(path):
    """Rewrite the teal glass vertex colours of FixedDetails in place (same size; idempotent). Returns the count."""
    data = bytearray(path.read_bytes())
    size = struct.unpack_from("<I", data, 12)[0]
    gltf = json.loads(data[20:20 + size])
    binary = 20 + size + 8
    changed = 0
    for mesh in gltf["meshes"]:
        for prim in mesh["primitives"]:
            if gltf["materials"][prim["material"]]["name"] != "FixedDetails" or "COLOR_0" not in prim["attributes"]:
                continue
            acc = gltf["accessors"][prim["attributes"]["COLOR_0"]]
            view = gltf["bufferViews"][acc["bufferView"]]
            assert acc["componentType"] == 5126, path
            comps = {"VEC3": 3, "VEC4": 4}[acc["type"]]
            stride = view.get("byteStride", comps * 4)
            start = binary + view.get("byteOffset", 0) + acc.get("byteOffset", 0)
            for i in range(acc["count"]):
                at = start + i * stride
                if tuple(round(v, 2) for v in struct.unpack_from("<3f", data, at)) == GLASS:
                    struct.pack_into("<3f", data, at, BODY_GREY, BODY_GREY, BODY_GREY)
                    changed += 1
    path.write_bytes(bytes(data))
    return changed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--workers", type=int, default=4)
    args = parser.parse_args()
    src = args.source.resolve()
    catalog = json.loads((src / "flat/catalog.json").read_text())
    cards = json.loads((src / "cards/catalog.json").read_text())
    manifest = json.loads((src / "manifest.json").read_text())
    jobs = []
    sprites = []
    models = {}
    for item in manifest["assets"]:
        key = role(item["asset"])
        name = "space_" + key
        target = ASSETS / "filament" / (name + ".glb")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src / item["model"], target)
        if key in OWNER_GLASS:
            print(f"{name}: {glass_to_body(target)} glass vertices take the owner colour", flush=True)
        models[key] = {"path": "filament/" + name + ".glb", "source": item["model"], **glb_stats(target)}

    for item in catalog["objects"]:
        key = role(item["asset"])
        prefix = "space_" + key
        folder = src / "flat" / item["slug"]
        layers = ["body", "trim", "shadow"] if item["has_body"] else ["full", "shadow"]
        # Shipped terrain frames are 384px; keep the authored geometry and centre.
        size = 384 if key.startswith("tile_") else item["size"]
        for layer in layers:
            for i in range(18):
                pattern = catalog[layer].format(slug=item["slug"], i=i)
                target = RES / f"{prefix}_{layer}_tilt_{i}.webp"
                jobs.append((folder / pattern, target, (size, size)))
        sprites.append({"role": key, "prefix": prefix, "frame": item["frame"], "contact": item["contact_plane"],
                        "tintable": item["has_body"], "size": size, "layers": layers})

    # Existing unlettered scene renders, centred in the shipped portrait dimensions.
    # The finished portraits/physical cards contain provisional titles: not UI art.
    card_names = {}
    for card in cards:
        key = card["id"]
        target = RES / f"space_card_{key}.webp"
        with Image.open(src / "cards/art" / (key + ".png")) as image:
            image = image.convert("RGBA")
            image.thumbnail((278, 390), Image.Resampling.LANCZOS)
            portrait = Image.new("RGBA", (278, 390))
            portrait.alpha_composite(image, ((278-image.width)//2, (390-image.height)//2))
            portrait.save(target, quality=90, method=4)
        card_names[key] = "space_card_" + key

    for level in range(1, 4):
        item = next(o for o in catalog["objects"] if o["asset"] == f"space_knight_l{level}")
        with Image.open(src / "flat" / item["slug"] / "full_tilt_10.png") as image:
            image = image.convert("RGBA").resize((256, 256), Image.Resampling.LANCZOS)
            portrait = Image.new("RGBA", (256, 360))
            portrait.alpha_composite(image, (0, 52))
            portrait.save(RES / f"space_card_knight_{level}.webp", quality=90, method=4)

    critters = []
    for folder in sorted((src / "critters").iterdir()):
        if not (folder / "critter.json").exists():
            continue
        c = json.loads((folder / "critter.json").read_text())
        name = "space_" + c["id"]
        target = ASSETS / "critters/space" / name
        (target / "frames").mkdir(parents=True, exist_ok=True)
        shutil.copyfile(folder / c["model"], target / (name + ".glb"))
        c["id"] = name
        c["model"] = name + ".glb"
        c["frames"]["span"] = c["frames"]["orthographic_scale"]
        c["frames"]["file"] = "{clip}{frame:02d}_h{heading}_t{tilt}.webp"
        c["frames"]["layout"] = "individual square frames"
        for clip in c["animations"]:
            clip["mode"] = "loop" if clip.pop("loop") else "once"
        # Explicit stages avoid merging the app's different anchored pattern defaults.
        c["behavior"]["pattern"] = "space-explicit"
        if "anchor" in c:
            c["anchor"] = {"at": "board-centre", "heading": 0}
            c["board_contact"] = "hold"
        (target / "critter.json").write_text(json.dumps(c, indent=2) + "\n")
        for strip in sorted((folder / "frames").glob("*.png")):
            clip, heading, tilt = strip.stem.rsplit("_", 2)
            with Image.open(strip) as image:
                side = image.height
                assert image.width == 4 * side, strip
                for f in range(4):
                    output = target / "frames" / f"{clip}{f:02d}_{heading}_{tilt}.webp"
                    if not output.exists() or output.stat().st_mtime < strip.stat().st_mtime:
                        image.crop((f*side, 0, (f+1)*side, side)).save(output, quality=90, method=4)
        critters.append("critters/space/" + name)
        print("Converted critter", name, flush=True)

    def convert(job):
        source, target, size = job
        if not target.exists() or target.stat().st_mtime < source.stat().st_mtime:
            save_webp(source, target, size)
    with ThreadPoolExecutor(max_workers=max(1, min(args.workers, 8))) as pool:
        futures = [pool.submit(convert, job) for job in jobs]
        for i, future in enumerate(as_completed(futures), 1):
            future.result()
            if i % 250 == 0 or i == len(jobs):
                print(f"Sprites {i}/{len(jobs)}", flush=True)

    def refs(names):
        return "listOf(" + ", ".join("R.drawable." + n for n in names) + ")"
    lines = ["package com.mauro.wgbg", "", "// Generated by tools/import_space.py; no player-facing names.",
             "internal object SpaceArtData {", "    val sprites by lazy { mapOf("]
    for s in sprites:
        lines.append(f'        "{s["role"]}" to {s["role"]}(),')
    lines += ["    ) }", ""]
    for s in sprites:
        lines += [f'    private fun {s["role"]}() = SpaceArt.Sprite(',
                  f'        "{s["prefix"]}", {s["frame"]}f, {s["contact"]}f, {s["size"]},',
                  f'        "{models[s["role"]]["path"]}",']
        for layer in ("body", "trim", "full", "shadow"):
            names = [f'{s["prefix"]}_{layer}_tilt_{i}' for i in range(18)] if layer in s["layers"] else []
            lines.append(f'        {layer} = {refs(names) if names else "emptyList()"},')
        lines.append("    )")
    lines += ["    val cards = mapOf("]
    lines += [f'        "{key}" to R.drawable.{value},' for key, value in card_names.items()]
    lines += ["    )", "    val models = mapOf("]
    lines += [f'        "{key}" to "{value["path"]}",' for key, value in models.items()]
    lines += ["    )", "    val critters = listOf(" + ", ".join(json.dumps(c) for c in critters) + ")", "}", ""]
    KT.write_text("\n".join(lines), encoding="utf-8")
    audit = {"source": "space-theme/manifest.json", "tilts": 18, "models": models, "sprites": sprites,
             "cards": card_names, "critters": critters}
    (ASSETS / "space_catalog.json").write_text(json.dumps(audit, indent=2) + "\n")
    print(f"Imported {len(models)} GLBs, {len(sprites)} sprite sets, {len(cards)} portraits, {len(critters)} critters", flush=True)


if __name__ == "__main__":
    main()
