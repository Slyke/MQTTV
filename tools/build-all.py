#!/usr/bin/env python3
"""Build Android APK and Tizen WGT concurrently and collect both with checksums."""
import argparse, concurrent.futures, hashlib, os, pathlib, re, shutil, subprocess, sys, time
ROOT=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument("--node",default=shutil.which("node"),help="Node.js executable (20+)")
parser.add_argument("--android-variant",choices=["debug","release"],default="debug",help="Android build type; release requires MQTTV signing variables")
parser.add_argument("--tizen-profile",help="Optional Tizen signing profile")
parser.add_argument("--tizen-cli",help="Tizen CLI executable when signing")
parser.add_argument("--service-bundle",action="store_true",help="Also build the experimental custom-install Tizen service archive")
args=parser.parse_args()
if not args.node: parser.error("Node.js 20+ is required; pass --node /path/to/node")
version=re.search(r"versionName\s+'([^']+)'",(ROOT/"mqtt-client/app/build.gradle").read_text()).group(1)
logs=ROOT/".integration/builds";logs.mkdir(parents=True,exist_ok=True)
dist=ROOT/"dist";dist.mkdir(exist_ok=True)
env=os.environ.copy();env["PATH"]=str(pathlib.Path(args.node).resolve().parent)+os.pathsep+env.get("PATH","")
if args.tizen_cli: env["TIZEN_CLI"]=str(pathlib.Path(args.tizen_cli).resolve())
def android(log):
    if args.android_variant=="release" and not all(env.get(k) for k in ["MQTTV_KEYSTORE_FILE","MQTTV_STORE_PASSWORD","MQTTV_KEY_ALIAS","MQTTV_KEY_PASSWORD"]):
        raise RuntimeError("Release packaging requires all four MQTTV signing variables; use --android-variant debug for a development APK")
    variant=args.android_variant.capitalize()
    subprocess.run(["bash","./gradlew",":app:testDebugUnitTest",":app:assemble"+variant,":app:lint"+variant],cwd=ROOT/"mqtt-client",env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
    output=dist/("TV-MQTT-API-Client-v"+version+".apk")
    shutil.copyfile(ROOT/("mqtt-client/app/build/outputs/apk/"+args.android_variant+"/app-"+args.android_variant+".apk"),output)
    return [output]
def tizen(log):
    npm=shutil.which("npm",path=env["PATH"])
    if not npm: raise RuntimeError("npm is required on the Node runtime PATH")
    source=ROOT/"tizen-client"
    if not (source/"node_modules").is_dir(): subprocess.run([npm,"ci","--ignore-scripts","--no-audit","--no-fund"],cwd=source,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
    for task in [("run","check"),("test",)]:
        subprocess.run([npm]+list(task),cwd=source,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
    command=[npm,"run","build"]
    extra=[]
    if args.tizen_profile: extra+=["--sign",args.tizen_profile]
    if args.service_bundle: extra+=["--service-bundle"]
    if extra: command+=["--"]+extra
    subprocess.run(command,cwd=source,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
    name="MQTTV-Tizen-v"+version+(".wgt" if args.tizen_profile else ".unsigned.wgt")
    output=dist/name;shutil.copyfile(source/"dist"/name,output)
    packages=[output]
    if args.service_bundle:
        service=dist/("MQTTV-Tizen-v"+version+".service.tar.gz");shutil.copyfile(source/"dist"/service.name,service);packages.append(service)
    return packages
outputs=[];failed=False
def run(name,worker):
    started=time.time();print("START",name,flush=True)
    with (logs/(name+".log")).open("w") as log: result=worker(log)
    print("PASS",name,"("+str(round(time.time()-started,1))+"s)",", ".join(p.name for p in result),flush=True)
    return result
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    futures={pool.submit(run,name,fn):name for name,fn in [("android",android),("tizen",tizen)]}
    for future in concurrent.futures.as_completed(futures):
        try: outputs.extend(future.result())
        except Exception as error:
            failed=True;print("FAIL",futures[future],str(error),"see",logs/(futures[future]+".log"),flush=True)
if outputs: (dist/"SHA256SUMS").write_text("".join(hashlib.sha256(p.read_bytes()).hexdigest()+"  "+p.name+"\n" for p in sorted(outputs)))
if failed: sys.exit(1)
print("Both packages are ready in",dist,flush=True)
