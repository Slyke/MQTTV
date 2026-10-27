'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {Processor,normalize}=require('../src/core.cjs');
function fixture(t,runner){const dir=fs.mkdtempSync(path.join(os.tmpdir(),'mqttv-test-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));const file=path.join(dir,'journal.json');return {file,processor:new Processor(file,{deduplicationEntries:16,maximumCommandAgeSeconds:120},runner)};}
const raw=(id,action,value,extra)=>Buffer.from(JSON.stringify(Object.assign({id,action,value},extra)));
test('concurrent MQTT/API retries execute once and canonicalize nested values',async t=>{
  let count=0;const f=fixture(t,async()=>{count++;await new Promise(r=>setTimeout(r,20));return {accepted:true};});
  const results=await Promise.all([f.processor.submit(raw('one','launch',{a:1,b:2}),false,false),f.processor.submit(raw('one','launch',{b:2,a:1},{timestamp:new Date().toISOString()}),false,true)]);
  assert.equal(count,1);assert.equal(results[1].duplicate,true);assert.equal(f.processor.data.outbox.length,1);
  const conflict=await f.processor.submit(raw('one','launch','other'),false,false);assert.equal(conflict.error.code,'id_conflict');assert.equal(count,1);
});
test('intent is on disk before execution; an interrupted service never repeats a command',async t=>{
  let f;f=fixture(t,async()=>{assert.equal(JSON.parse(fs.readFileSync(f.file)).entries[0].ack,null);return {accepted:true};});
  await f.processor.submit(raw('durable','home'),false,false);
  const data=JSON.parse(fs.readFileSync(f.file));data.entries[0].ack=null;fs.writeFileSync(f.file,JSON.stringify(data));
  let replayed=0;const recovered=new Processor(f.file,{deduplicationEntries:16,maximumCommandAgeSeconds:120},()=>{replayed++;});
  const ack=await recovered.submit(raw('durable','home'),false,false);assert.equal(ack.error.code,'execution_interrupted');assert.equal(ack.duplicate,true);assert.equal(replayed,0);
});
test('retained commands are rejected before duplicate lookup',async t=>{
  let count=0;const f=fixture(t,()=>{count++;return {};});
  await f.processor.submit(raw('same','home'),false,false);
  for(const id of ['same','new'])assert.equal((await f.processor.submit(raw(id,'home'),true,true)).error.code,'retained_command');
  assert.equal(count,1);
});
test('expired, malformed, oversized and future commands never reach the TV',async t=>{
  let count=0;const f=fixture(t,()=>{count++;return {};});
  for(const [payload,code] of [
    [Buffer.from('{} {}'),'invalid_json'],[Buffer.alloc(16385),'payload_too_large'],[Buffer.from('[]'),'invalid_json'],
    [raw('old','home',null,{timestamp:'2000-01-01T00:00:00Z'}),'expired_command'],
    [raw('expired','home',null,{expires_at:'2000-01-01T00:00:00Z'}),'expired_command'],
    [raw('future','home',null,{timestamp:'2099-01-01T00:00:00Z'}),'invalid_timestamp'],
    [raw('invalid','home',null,{timestamp:123}),'invalid_timestamp']]){
    assert.equal((await f.processor.submit(payload,false,false)).error.code,code);
  }assert.equal(count,0);
});
test('full MQTT ack outbox prevents execution; REST acks stay out of the outbox',async t=>{
  let count=0;const f=fixture(t,()=>{count++;return {};});
  await f.processor.submit(raw('rest','status'),false,false);assert.equal(f.processor.data.outbox.length,0);
  f.processor.data.outbox=Array.from({length:512},(_,i)=>({token:String(i),ack:{}}));
  await assert.rejects(f.processor.submit(raw('mqtt','home'),false,true),e=>e.code==='outbox_full');assert.equal(count,1);
});
test('configuration defaults reject unsafe topics, external TV targets and conflicting ports',()=>{
  const a=normalize({tv:{name:'Living Room TV'}}),b=normalize({tv:{name:'Living Room TV'}});
  assert.equal(a.mqtt.effectiveBaseTopic,'tvs/living-room-tv');assert.ok(a.api.passkey.length>=32);assert.notEqual(a.api.passkey,b.api.passkey);
  assert.throws(()=>normalize({tv:{host:'10.0.0.1'}}),/loopback/);
  assert.throws(()=>normalize({mqtt:{baseTopic:'tvs/+'}}),/base topic/);
  assert.throws(()=>normalize({api:{http:{enabled:true,port:8790}}}),/reserved/);
  assert.throws(()=>normalize({api:{http:{enabled:true,port:9000},https:{enabled:true,port:9000}}}),/differ/);
});

test('TLS verification settings reject ambiguous booleans and require an explicit trust source',()=>{
  assert.throws(()=>normalize({mqtt:{uri:'mqtts://broker:8883',tls:{insecureSkipVerify:'false'}}}),/booleans/);
  assert.throws(()=>normalize({mqtt:{uri:'mqtts://broker:8883',tls:{trustSystemCAs:false}}}),/CA certificate/);
  assert.equal(normalize({mqtt:{uri:'mqtt://broker:1883',tls:{trustSystemCAs:false}}}).mqtt.tls.trustSystemCAs,false);
  assert.throws(()=>normalize({api:{bindAddress:null}}),/address/);
});
