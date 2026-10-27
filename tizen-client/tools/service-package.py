#!/usr/bin/env python3
"""Create the account-free service archive; contains no credentials or TV binaries."""
import argparse,gzip,hashlib,io,pathlib,tarfile
p=argparse.ArgumentParser();p.add_argument("widget",type=pathlib.Path);p.add_argument("root",type=pathlib.Path);p.add_argument("output",type=pathlib.Path);a=p.parse_args()
files={"service/index.js":a.widget/"service/index.js","bootstrap.js":a.root/"bootstrap.js","mqttv.service":a.root/"mqttv.service"}
for name in ["index.html","app.js","style.css"]:files["ui/"+name]=a.widget/name
for name in ["LICENSE","THIRD_PARTY_NOTICES.txt"]:files[name]=a.widget/name
a.output.parent.mkdir(parents=True,exist_ok=True)
with a.output.open("wb") as raw,gzip.GzipFile(fileobj=raw,mode="wb",mtime=0) as gz,tarfile.open(fileobj=gz,mode="w") as archive:
    for name,source in sorted(files.items()):
        data=source.read_bytes();entry=tarfile.TarInfo(name);entry.size=len(data);entry.mode=0o600;entry.mtime=0;archive.addfile(entry,io.BytesIO(data))
print(hashlib.sha256(a.output.read_bytes()).hexdigest(),a.output.name)
