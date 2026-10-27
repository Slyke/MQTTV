'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),http=require('node:http'),crypto=require('node:crypto');
const WebSocket=require('ws');
const {Api,identity,publicIdentity}=require('../src/api.cjs');
const {Processor,normalize}=require('../src/core.cjs');
function request(port,route,method,auth,data){
  return new Promise((resolve,reject)=>{
    const req=http.request({host:'127.0.0.1',port,path:route,method:method||'GET',headers:Object.assign({},auth?{'X-API-Key':auth}:{},data?{'Content-Type':'application/json'}:{})},res=>{
      const chunks=[];res.on('data',b=>chunks.push(b));res.on('end',()=>resolve({status:res.statusCode,body:JSON.parse(Buffer.concat(chunks).toString())}));
    });req.on('error',reject);req.end(data?JSON.stringify(data):undefined);
  });
}
test('real REST/WebSocket endpoints require header auth and share deduplication',async t=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'mqttv-api-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
  let calls=0;
  const processor=new Processor(path.join(dir,'commands.json'),{deduplicationEntries:16,maximumCommandAgeSeconds:120},async()=>{calls++;return {accepted:true};});
  const config=normalize({api:{passkey:'private-test-key'}}).api;config.http={enabled:true,port:0};
  const api=new Api(config,dir,processor,async()=>({power:'active',network:{ips:{lo:['127.0.0.1']}}}));
  await api.start();t.after(()=>api.stop());const port=api.servers[0].address().port;
  assert.equal((await request(port,'/api/state')).status,401);
  assert.equal((await request(port,'/api/state?passkey=private-test-key')).status,401);
  assert.equal((await request(port,'/api/state','GET','private-test-key')).body.state.power,'active');
  const cmd={id:'shared',action:'home'};
  assert.equal((await request(port,'/api/command','POST','private-test-key',cmd)).body.ok,true);
  const ws=new WebSocket('ws://127.0.0.1:'+port+'/api/ws',{headers:{'X-API-Key':'private-test-key'}});t.after(()=>ws.terminate());
  const ack=await new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>reject(new Error('WebSocket ack timed out')),5000);
    ws.on('error',reject);ws.on('open',()=>ws.send(JSON.stringify(cmd)));
    ws.on('message',raw=>{const value=JSON.parse(raw);if(value.ack){clearTimeout(timer);resolve(value.ack);}});
  });
  assert.equal(ack.duplicate,true);assert.equal(calls,1);assert.equal(processor.data.outbox.length,0);
  const bad=new WebSocket('ws://127.0.0.1:'+port+'/api/ws?key=private-test-key');
  await new Promise((resolve,reject)=>{bad.on('unexpected-response',(req,res)=>{assert.equal(res.statusCode,401);req.destroy();resolve();});bad.on('open',()=>reject(new Error('Anonymous WebSocket opened')));bad.on('error',()=>{});});
});
test('HTTPS identity persists and public metadata excludes the private key',t=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'mqttv-identity-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
  const a=identity(dir),b=identity(dir);assert.equal(a.privateKey,b.privateKey);assert.equal(a.certificate,b.certificate);
  const info=publicIdentity(a);assert.equal(JSON.stringify(info).includes('PRIVATE KEY'),false);assert.ok(info.public_key_pem.includes('BEGIN PUBLIC KEY'));
  const cert=new crypto.X509Certificate(a.certificate);assert.equal(cert.publicKey.export({type:'spki',format:'pem'}),a.publicKey.replace(/\r\n/g,'\n'));
});

test('browser settings are public only as static assets; credentials require API header authentication',async t=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'mqttv-settings-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
  const config=normalize({mqtt:{password:'broker-password'},api:{passkey:'settings-passkey'}});
  const options=Object.assign({},config.api,{http:{enabled:true,port:0}});let saved=null,reloaded=false;
  const settings={assets:path.resolve(__dirname,'../ui'),read:()=>config,save:value=>{saved=normalize(value);return saved;},reload:()=>{reloaded=true;},pair:async()=>{}};
  const api=new Api(options,dir,new Processor(path.join(dir,'commands.json'),config.service,async()=>({})),async()=>({power:'active'}),settings);
  await api.start();t.after(()=>api.stop());const port=api.servers[0].address().port;
  const page=await fetch('http://127.0.0.1:'+port+'/settings');const html=await page.text();assert.equal(page.status,200);assert.ok(html.includes('MQTTV'));assert.equal(html.includes('broker-password'),false);
  assert.equal((await request(port,'/api/settings')).status,401);
  const authorized=await request(port,'/api/settings','GET','settings-passkey');assert.equal(authorized.body.config.mqtt.password,'broker-password');
  const changed=JSON.parse(JSON.stringify(config));changed.tv.name='Updated TV';
  assert.equal((await request(port,'/api/settings','POST','settings-passkey',changed)).body.saved,true);
  await new Promise(r=>setTimeout(r,100));assert.equal(saved.tv.name,'Updated TV');assert.equal(reloaded,true);
});

test('loopback setup preflight reveals no config and all settings reads need the private setup key',async t=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'mqttv-bootstrap-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
  const service=require('../src/runtime.cjs').createService({directory:dir});await service.start();t.after(()=>service.stop());
  const preflight=await new Promise((resolve,reject)=>{const req=http.request({host:'127.0.0.1',port:8790,path:'/setup',method:'OPTIONS',headers:{Origin:'null','Access-Control-Request-Headers':'X-MQTTV-Setup'}},res=>{res.resume();res.on('end',()=>resolve(res));});req.on('error',reject);req.end();});
  assert.equal(preflight.statusCode,204);assert.equal((await request(8790,'/setup')).status,401);
  const key=fs.readFileSync(path.join(dir,'setup-key.txt'),'utf8');
  const value=await new Promise((resolve,reject)=>{http.get({host:'127.0.0.1',port:8790,path:'/setup',headers:{'X-MQTTV-Setup':key}},res=>{const chunks=[];res.on('data',b=>chunks.push(b));res.on('end',()=>resolve(JSON.parse(Buffer.concat(chunks))));}).on('error',reject);});
  assert.ok(value.config.api.passkey.length>=32);assert.equal(value.running,false);
});
