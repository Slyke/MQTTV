import {build} from 'esbuild';
import {readFile,writeFile,mkdir,readdir,rm,copyFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import zlib from 'node:zlib';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const gradle=await readFile(path.join(root,'../mqtt-client/app/build.gradle'),'utf8');
const version=/versionName\s+'([^']+)'/.exec(gradle)[1];
if(!/^\d+\.\d+\.\d+$/.test(version))throw Error('Expected a three-part Android version');
if(process.env.GITHUB_REF_TYPE==='tag'&&process.env.GITHUB_REF_NAME!=='v'+version)throw Error('Release tag must match the Android/Tizen version');
const stage=path.join(root,'build/widget'),dist=path.join(root,'dist');
await rm(stage,{recursive:true,force:true});await mkdir(path.join(stage,'service'),{recursive:true});await mkdir(dist,{recursive:true});
const bundle=await build({entryPoints:[path.join(root,'src/entry.cjs')],bundle:true,platform:'node',format:'cjs',target:'node8',outfile:path.join(stage,'service/index.js'),metafile:true,legalComments:'eof',define:{__MQTTV_VERSION__:JSON.stringify(version)}});
const packages=new Map();
for(const input of Object.keys(bundle.metafile.inputs)){
  let current=path.dirname(path.resolve(input));
  if(!current.includes(path.sep+'node_modules'+path.sep))continue;
  while(current!==path.dirname(current)){
    try{
      const info=JSON.parse(await readFile(path.join(current,'package.json'),'utf8'));
      const texts=[];
      for(const file of await readdir(current))if(/^(licen[sc]e|copying|notice)([._-]|$)/i.test(file)){
        try{texts.push(file+'\n'+await readFile(path.join(current,file),'utf8'));}catch{}
      }
      packages.set(info.name,{version:info.version,license:info.license,texts});break;
    }catch{}current=path.dirname(current);
  }
}
let notices='Third-party components bundled in MQTTV Tizen\nnode-forge is used under its BSD license option.\n\n';
for(const [name,pkg] of [...packages].sort())notices+=name+' '+pkg.version+' ('+pkg.license+')\n'+pkg.texts.join('\n')+'\n\n';
await writeFile(path.join(stage,'THIRD_PARTY_NOTICES.txt'),notices);
await copyFile(path.join(root,'../LICENSE'),path.join(stage,'LICENSE'));
for(const name of ['index.html','style.css','app.js'])await copyFile(path.join(root,'ui',name),path.join(stage,name));
const onBoot=process.argv.includes('--on-boot');
const serviceAttributes=onBoot?' on-boot="true" auto-restart="true"':'';
await writeFile(path.join(stage,'config.xml'),'<?xml version="1.0" encoding="UTF-8"?>\n'+
'<widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" id="https://github.com/Slyke/MQTTV/tizen" version="'+version+'" viewmodes="maximized">\n'+
'<tizen:application id="MQTTVTizen.App" package="MQTTVTizen" required_version="6.0"/><name>MQTTV</name><description>MQTT and API control on Samsung Tizen TVs</description><content src="index.html"/><icon src="icon.png"/>'+
'<feature name="http://tizen.org/feature/screen.size.all"/><feature name="http://tizen.org/feature/web.service"/><access origin="*" subdomains="true"/>'+
'<tizen:privilege name="http://tizen.org/privilege/internet"/><tizen:privilege name="http://tizen.org/privilege/application.launch"/><tizen:privilege name="http://tizen.org/privilege/filesystem.read"/><tizen:profile name="tv-samsung"/>'+
'<tizen:metadata key="http://samsung.com/tv/metadata/multitasking.support" value="true"/>'+
'<tizen:service id="MQTTVTizen.Service"'+serviceAttributes+'><tizen:content src="service/index.js"/><tizen:name>MQTTV connections</tizen:name><tizen:description>Background MQTT and API service</tizen:description></tizen:service>'+
'<tizen:setting screen-orientation="landscape" context-menu="disable" background-support="disable" encryption="disable" install-location="auto" hwkey-event="enable"/></widget>\n');
// Small code-drawn TV icon; no image tooling or downloaded assets are required.
const pixels=Buffer.alloc(128*129*4);
for(let y=0;y<128;y++){
  const offset=y*513;pixels[offset]=0;
  for(let x=0;x<128;x++){
    const tv=x>=20&&x<=107&&y>=30&&y<=91;
    const screen=x>=27&&x<=100&&y>=37&&y<=84;
    const stand=y>=98&&y<=103&&x>=45&&x<=82;
    const color=(tv&&!screen)||stand?[131,239,197]:[16,24,32];
    const i=offset+1+x*4;pixels[i]=color[0];pixels[i+1]=color[1];pixels[i+2]=color[2];pixels[i+3]=255;
  }
}
function crc(data){let c=0xffffffff;for(const b of data){c^=b;for(let i=0;i<8;i++)c=c&1?(c>>>1)^0xedb88320:c>>>1;}return(c^0xffffffff)>>>0;}
function chunk(type,data){const name=Buffer.from(type),length=Buffer.alloc(4),sum=Buffer.alloc(4);length.writeUInt32BE(data.length);sum.writeUInt32BE(crc(Buffer.concat([name,data])));return Buffer.concat([length,name,data,sum]);}
const header=Buffer.alloc(13);header.writeUInt32BE(128,0);header.writeUInt32BE(128,4);header[8]=8;header[9]=6;
await writeFile(path.join(stage,'icon.png'),Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',header),chunk('IDAT',zlib.deflateSync(pixels.subarray(0,513*128))),chunk('IEND',Buffer.alloc(0))]));
const stem='MQTTV-Tizen-v'+version+(onBoot?'-boot':'');
const unsigned=path.join(dist,stem+'.unsigned.wgt');
const python=process.env.PYTHON||'python3';
let result=spawnSync(python,[path.join(root,'tools/package.py'),stage,unsigned],{stdio:'inherit'});
if(result.status!==0)throw Error('WGT packaging failed');
const signAt=process.argv.indexOf('--sign');
if(signAt>=0){
  const profile=process.argv[signAt+1];if(!profile)throw Error('--sign requires a Tizen security profile');
  const cli=process.env.TIZEN_CLI||'tizen';
  result=spawnSync(cli,['package','-t','wgt','-s',profile,'--',stage],{stdio:'inherit'});
  if(result.status!==0)throw Error('Tizen signing failed');
  const signed=(await readdir(stage)).filter(f=>f.endsWith('.wgt'));
  if(signed.length!==1)throw Error('Expected one signed WGT');
  await copyFile(path.join(stage,signed[0]),path.join(dist,stem+'.wgt'));
}
if(process.argv.includes('--service-bundle')){
  result=spawnSync(python,[path.join(root,'tools/service-package.py'),stage,path.join(root,'root'),path.join(dist,stem+'.service.tar.gz')],{stdio:'inherit'});
  if(result.status!==0)throw Error('Service archive packaging failed');
}
console.log('Tizen '+version+' built'+(signAt<0?' (unsigned; sign for the target TV before installing)':' and signed'));
