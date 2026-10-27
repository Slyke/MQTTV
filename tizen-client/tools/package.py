#!/usr/bin/env python3
"""Create a deterministic unsigned WGT from the explicit staging directory."""
import argparse, hashlib, os, pathlib, zipfile
parser=argparse.ArgumentParser()
parser.add_argument("source",type=pathlib.Path)
parser.add_argument("output",type=pathlib.Path)
args=parser.parse_args()
root=args.source.resolve()
args.output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(args.output,"w",compression=zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
    for file in sorted(root.rglob("*")):
        if not file.is_file(): continue
        if file.is_symlink() or not file.resolve().is_relative_to(root): raise SystemExit("Unsafe package path")
        name=file.relative_to(root).as_posix()
        if name.endswith((".local.json",".p12",".pem",".key")) or name.startswith("."): raise SystemExit("Private file in staging directory")
        entry=zipfile.ZipInfo(name,(1980,1,1,0,0,0))
        entry.external_attr=0o100644<<16
        entry.compress_type=zipfile.ZIP_DEFLATED
        archive.writestr(entry,file.read_bytes())
print(hashlib.sha256(args.output.read_bytes()).hexdigest(),args.output.name)
