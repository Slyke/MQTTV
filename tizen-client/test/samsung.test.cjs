'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {normalize}=require('../src/core.cjs');const {Samsung}=require('../src/samsung.cjs');const {Runtime}=require('../src/runtime.cjs');
test('native IME encodes Unicode and acknowledgements never echo entered text',async()=>{
  const driver=new Samsung(normalize({}).tv,()=>{});let sent;
  driver.send=async p=>{sent=p;return {accepted:true,method:'samsung_remote'};};
  const value='Jellyfin \u00e9 \u{1f4fa}',ack=await driver.execute({action:'text',value});
  assert.equal(Buffer.from(sent.Cmd,'base64').toString(),value);assert.equal(sent.TypeOfRemote,'SendInputString');assert.equal(JSON.stringify(ack).includes(value),false);
  await assert.rejects(driver.execute({action:'select_all'}),e=>e.code==='unsupported_action');
});
test('invalid commands cannot issue volume changes, launch URLs or send factory keys',async()=>{
  const driver=new Samsung(normalize({}).tv,()=>{});let calls=0;driver.soap=driver.api=driver.key=async()=>{calls++;return {};};
  for(const command of [{action:'volume_set',value:101},{action:'volume_set',value:1.2},{action:'launch',value:'https://example.com/'},{action:'key',value:'KEY_FACTORY'}])await assert.rejects(driver.execute(command));
  assert.equal(calls,0);
});
test('telemetry marks unavailable foreground data and excludes connection secrets',async t=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'mqttv-state-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
  const runtime=new Runtime(normalize({mqtt:{password:'broker-secret'},tv:{token:'remote-secret'},api:{passkey:'api-secret'}}),dir);
  runtime.driver.api=async()=>({device:{OS:'Tizen',type:'Samsung SmartTV',PowerState:'on',name:'Living Room TV',modelName:'SAMPLE-TV'}});
  runtime.driver.soap=async name=>name==='GetVolume'?12:0;
  const state=await runtime.snapshot(),json=JSON.stringify(state);
  for(const secret of ['broker-secret','remote-secret','api-secret'])assert.equal(json.includes(secret),false);
  assert.equal(state.foreground_app.available,false);assert.equal(state.volume,12);assert.equal(state.muted,false);assert.equal(state.driver,'samsung-tizen');
});
