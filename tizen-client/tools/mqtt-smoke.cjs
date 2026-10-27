'use strict';
// Broker-only smoke test. The television driver is a fixture: no TV is contacted.
const fs=require('fs'),os=require('os'),path=require('path'),crypto=require('crypto'),mqtt=require('mqtt');
const assert=require('assert');
const {normalize,Failure}=require('../src/core.cjs');
const {Runtime}=require('../src/runtime.cjs');
const file=process.argv[2];
if(!file)throw Error('Usage: node tools/mqtt-smoke.cjs /path/to/private-config.json');
const original=JSON.parse(fs.readFileSync(file,'utf8'));
const id=crypto.randomBytes(8).toString('hex'),base='tvs/mqttv-smoke/'+id;
const directory=fs.mkdtempSync(path.join(os.tmpdir(),'mqttv-mqtt-'));
const config=normalize({mqtt:Object.assign({},original.mqtt,{clientId:'mqttv-it-'+id,baseTopic:base}),tv:{driver:'samsung-tizen',name:'Transport test'},service:{statePollSeconds:1,stateHeartbeatSeconds:1}});
const tls=config.mqtt.tls,uri=config.mqtt.uri.replace(/^(ssl|tls):/,'mqtts:').replace(/^tcp:/,'mqtt:');
let ca=tls.caCertificatePem;
if(tls.caFile){const raw=fs.readFileSync(path.resolve(path.dirname(file),tls.caFile));fs.writeFileSync(path.join(directory,path.basename(tls.caFile)),raw);config.mqtt.tls.caFile=path.basename(tls.caFile);ca=raw;}
if(ca&&tls.trustSystemCAs)ca=(require('tls').rootCertificates||[]).concat([ca]);
const client=mqtt.connect(uri,{clientId:'mqttv-observer-'+id,username:config.mqtt.username,password:config.mqtt.password,clean:true,reconnectPeriod:0,connectTimeout:8000,rejectUnauthorized:!tls.insecureSkipVerify,...(ca?{ca}:{})});
const events=[],waiters=[];
client.on('error',()=>{});
client.on('message',(topic,data)=>{if(!data.length)return;let value;try{value=JSON.parse(data);}catch(_){return;}const event={topic,value};events.push(event);for(const waiter of [...waiters])if(waiter.match(event)){waiters.splice(waiters.indexOf(waiter),1);clearTimeout(waiter.timer);waiter.resolve(event);}});
function next(match){const found=events.find(match);if(found)return Promise.resolve(found);return new Promise((resolve,reject)=>{const w={match,resolve,timer:setTimeout(()=>{waiters.splice(waiters.indexOf(w),1);reject(Error('MQTT smoke response timed out'));},10000)};waiters.push(w);});}
function publish(topic,value,retain){return new Promise((resolve,reject)=>client.publish(base+topic,typeof value==='string'?value:JSON.stringify(value),{qos:1,retain:Boolean(retain)},e=>e?reject(e):resolve()));}
let runtime=null,count=0;
function fixture(){
  const r=new Runtime(config,directory);
  r.driver={snapshot:async()=>({reachable:true,power:'active',state:'on',driver:'transport-test-fixture',test_scope:'host_only'}),connect:async()=>{},close:()=>{},
    execute:async(command,snapshot)=>{if(command.action!=='status')throw new Failure('unsupported_action','Only status is permitted in this smoke test');count++;return snapshot();}};
  return r;
}
async function main(){
  await new Promise((resolve,reject)=>{client.once('connect',resolve);client.once('error',reject);});
  await new Promise((resolve,reject)=>client.subscribe([base+'/stat/RESULT',base+'/tele/STATE',base+'/tele/LWT'],{qos:1},e=>e?reject(e):resolve()));
  await publish('/cmnd/COMMAND',{id:'retained',action:'home'},true);
  runtime=fixture();await runtime.start();
  const retained=await next(x=>x.value.command_id==='retained');assert.equal(retained.value.error.code,'retained_command');assert.equal(count,0);
  console.log('PASS retained commands rejected');
  await publish('/cmnd/COMMAND','',true);
  const online=await next(x=>x.topic.endsWith('/tele/LWT')&&x.value.online===true);assert.ok(online.value.last_update);
  const state=await next(x=>x.topic.endsWith('/tele/STATE'));assert.ok(Date.parse(state.value.expires_at)>Date.now());
  console.log('PASS retained availability/state and heartbeat freshness');
  const command={id:'round-trip',action:'status',timestamp:new Date().toISOString()};
  await publish('/cmnd/COMMAND',command);const ack=await next(x=>x.value.command_id===command.id&&x.value.ok&&!x.value.duplicate);assert.equal(ack.value.result.power,'active');assert.equal(count,1);
  console.log('PASS broker command/ack round trip');
  await publish('/cmnd/COMMAND',command);await next(x=>x.value.command_id===command.id&&x.value.duplicate);assert.equal(count,1);
  console.log('PASS live duplicate suppressed');
  await publish('/cmnd/COMMAND',{id:command.id,action:'volume_set',value:10});const conflict=await next(x=>x.value.command_id===command.id&&x.value.error);assert.equal(conflict.value.error.code,'id_conflict');assert.equal(count,1);
  console.log('PASS conflicting command ID rejected');
  await runtime.stop();runtime=null;
  events.length=0;
  runtime=fixture();await runtime.start();await next(x=>x.topic.endsWith('/tele/LWT')&&x.value.online);
  await publish('/cmnd/COMMAND',command);await next(x=>x.value.command_id===command.id&&x.value.duplicate);assert.equal(count,1);
  console.log('PASS deduplication survives service restart');
}
main().catch(()=>{console.error('FAIL broker smoke test; see configuration/connectivity');process.exitCode=1;}).finally(async()=>{
  if(runtime)await runtime.stop();
  if(client.connected)for(const topic of ['/cmnd/COMMAND','/tele/STATE','/tele/LWT'])await publish(topic,'',true);
  await new Promise(resolve=>client.end(true,resolve));
  for(const waiter of waiters)clearTimeout(waiter.timer);
  fs.rmSync(directory,{recursive:true,force:true});
});
