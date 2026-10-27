'use strict';
const http = require('http');
const WebSocket = require('ws');
const { Failure, integer } = require('./core.cjs');
const KEYS = { up:'KEY_UP',down:'KEY_DOWN',left:'KEY_LEFT',right:'KEY_RIGHT',ok:'KEY_ENTER',back:'KEY_RETURN',home:'KEY_HOME',input:'KEY_SOURCE',
  options:'KEY_TOOLS',info:'KEY_INFO',play:'KEY_PLAY',pause:'KEY_PAUSE',play_pause:'KEY_PLAYPAUSE',stop:'KEY_STOP',rewind:'KEY_REWIND',fast_forward:'KEY_FF',
  next:'KEY_FF',previous:'KEY_REWIND',channel_up:'KEY_CHUP',channel_down:'KEY_CHDOWN',volume_up:'KEY_VOLUP',volume_down:'KEY_VOLDOWN' };
function request(host, port, route, method, body, headers, timeout) {
  return new Promise((resolve, reject) => {
    const raw = body === undefined ? null : Buffer.from(body);
    const req = http.request({ host, port, path:route, method, headers:Object.assign({},headers,raw ? {'Content-Length':raw.length} : {}) }, res => {
      let size=0; const chunks=[];
      res.on('data', b => { size+=b.length; if(size>262144) res.destroy(new Error('Response too large')); else chunks.push(b); });
      res.on('error',reject);
      res.on('end',() => res.statusCode>=200 && res.statusCode<300 ? resolve(Buffer.concat(chunks).toString()) : reject(new Failure('tv_api_error','TV API returned HTTP '+res.statusCode)));
    });
    const timer=setTimeout(()=>req.destroy(new Failure('tv_timeout','TV API timed out')),timeout);
    req.on('close',()=>clearTimeout(timer));req.on('error',reject);req.end(raw);
  });
}
class Samsung {
  constructor(config, persist) { this.c=config;this.persist=persist;this.socket=null;this.ready=false;this.connecting=null;this.closed=false; }
  api(route, method, body) { return request(this.c.host,8001,'/api/v2/'+route,method||'GET',body,{},this.c.timeoutMs).then(s=>s?JSON.parse(s):{}); }
  async soap(method, value) {
    const extra = value === undefined ? '' : '<Desired'+(method==='SetVolume'?'Volume':'Mute')+'>'+value+'</Desired'+(method==='SetVolume'?'Volume':'Mute')+'>';
    const body='<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:'+method+' xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><InstanceID>0</InstanceID><Channel>Master</Channel>'+extra+'</u:'+method+'></s:Body></s:Envelope>';
    const raw=await request(this.c.host,9197,'/upnp/control/RenderingControl1','POST',body,{'Content-Type':'text/xml; charset="utf-8"',SOAPACTION:'"urn:schemas-upnp-org:service:RenderingControl:1#'+method+'"'},this.c.timeoutMs);
    const field=method==='GetVolume'?'CurrentVolume':method==='GetMute'?'CurrentMute':null;
    if(!field)return;
    const m=raw.match(new RegExp('<(?:[\\w-]+:)?'+field+'[^>]*>([^<]*)</'));
    if(!m)throw new Failure('invalid_tv_response','TV audio status was missing');
    return Number(m[1]);
  }
  connect() {
    if(this.ready)return Promise.resolve();
    if(this.connecting)return this.connecting;
    if(this.closed)return Promise.reject(new Failure('control_stopped','TV control is stopped'));
    this.connecting=new Promise((resolve,reject)=>{
      let settled=false;
      const query='?name='+encodeURIComponent(Buffer.from(this.c.remoteName).toString('base64'))+(this.c.token?'&token='+encodeURIComponent(this.c.token):'');
      const ws=this.socket=new WebSocket('wss://'+this.c.host+':8002/api/v2/channels/samsung.remote.control'+query,{rejectUnauthorized:false,handshakeTimeout:this.c.timeoutMs,maxPayload:262144,perMessageDeflate:false});
      const timer=setTimeout(()=>{fail(new Failure('pairing_required','Allow MQTTV network control on the TV, then retry'));ws.terminate();},this.c.timeoutMs+45000);
      const fail=e=>{if(!settled){settled=true;clearTimeout(timer);reject(e);}this.ready=false;};
      ws.on('message',raw=>{
        let m;try{m=JSON.parse(raw.toString());}catch(_){return;}
        if(m.event==='ms.channel.connect') {
          try {
            if(m.data && m.data.token){this.c.token=String(m.data.token);this.persist();}
            this.ready=true;if(!settled){settled=true;clearTimeout(timer);resolve();}
          } catch(e){fail(e);ws.terminate();}
        } else if(m.event==='ms.channel.unauthorized'){fail(new Failure('pairing_required','Allow MQTTV network control on the TV'));ws.terminate();}
      });
      ws.on('error',()=>fail(new Failure('control_unavailable','Samsung remote connection is unavailable')));
      ws.on('close',()=>{this.ready=false;fail(new Failure('control_disconnected','Samsung remote disconnected; commands are not replayed'));});
    }).then(r=>{this.connecting=null;return r;},e=>{this.connecting=null;throw e;});
    return this.connecting;
  }
  async send(params) {
    await this.connect();
    const socket=this.socket;
    return new Promise((resolve,reject)=>{
      if(!this.ready || socket.readyState!==WebSocket.OPEN)return reject(new Failure('control_disconnected','Samsung remote is not connected'));
      socket.send(JSON.stringify({method:'ms.remote.control',params}),e=>e?reject(new Failure('execution_uncertain','The remote send failed; do not repeat with a new command ID')):resolve({accepted:true,method:'samsung_remote'}));
    });
  }
  key(key) { return this.send({Cmd:'Click',DataOfCmd:key,Option:'false',TypeOfRemote:'SendRemoteKey'}); }
  async snapshot() {
    const identity=await this.api('');
    if(identity.device.OS!=='Tizen' || identity.device.type!=='Samsung SmartTV')throw new Failure('wrong_device','The local endpoint is not a Samsung Tizen TV');
    const audio=await Promise.all([this.soap('GetVolume').catch(()=>null),this.soap('GetMute').catch(()=>null)]);
    return { reachable:true,driver:'samsung-tizen',power:identity.device.PowerState==='on'?'active':'standby',state:identity.device.PowerState==='on'?'on':'off',
      volume:audio[0],muted:audio[1]===null?null:Boolean(audio[1]),input:null,content:null,
      control:{method:'samsung_remote',ready:this.ready,error:this.ready?null:'pairing_or_connection_required'},
      capabilities:{power:true,volume:true,mute:true,navigation:true,launch:true,input:true,text:true,backspace:false,select_all:false},
      foreground_app:{available:false,reason:'not_exposed_by_samsung_local_api'},media:{available:false,reason:'not_exposed_by_samsung_local_api'},
      television:{name:identity.device.name,model:identity.device.modelName,os:identity.device.OS},
      text_input:{mode:'samsung_ime',available:true,scope:'focused_app'} };
  }
  async execute(command, snapshot) {
    const action=command.action,value=command.value;
    if(action==='status'||action==='status_refresh')return snapshot();
    if(action==='apps')return {applications:this.c.applications};
    if(action==='volume_set'){integer(value,0,100,'volume_set');await this.soap('SetVolume',value);return {requested_volume:value};}
    if(['mute','mute_on','mute_off'].includes(action)){
      const target=action==='mute'?!Boolean(await this.soap('GetMute')):action==='mute_on';
      await this.soap('SetMute',target?1:0);return {requested_mute:target};
    }
    if(action==='power_on'||action==='power_off'){
      const state=await this.api('');
      if((state.device.PowerState==='on')===(action==='power_on'))return {already_requested_state:true};
      return this.key(action==='power_on'?'KEY_POWERON':'KEY_POWEROFF');
    }
    if(action==='input'){
      if(typeof value!=='string'||!/^hdmi[1-4]$/.test(value))throw new Failure('invalid_value','Input requires hdmi1..hdmi4');
      return this.key('KEY_HDMI'+value.substring(4));
    }
    if(action==='launch'){
      if(typeof value!=='string'||!value||value.length>128)throw new Failure('invalid_value','An app name or ID is required');
      const app=this.c.applications.find(a=>a.id===value || a.name.toLowerCase()===value.toLowerCase());
      const id=app?app.id:value;
      if(!/^[a-zA-Z0-9._-]+$/.test(id))throw new Failure('invalid_value','Invalid application ID');
      await this.api('applications/'+encodeURIComponent(id),'POST','');return {accepted:true,application:app?app.name:id};
    }
    if(action==='text'){
      if(typeof value!=='string'||!value||value.length>240)throw new Failure('invalid_value','Text requires 1-240 characters');
      return this.send({Cmd:Buffer.from(value).toString('base64'),DataOfCmd:'base64',TypeOfRemote:'SendInputString'});
    }
    if(action==='backspace'||action==='select_all')throw new Failure('unsupported_action','Samsung does not expose a verified generic text-editing command');
    const key=action==='key'?value:action;
    if(!Object.prototype.hasOwnProperty.call(KEYS,key))throw new Failure('unknown_action','Unknown remote key or action');
    return this.key(KEYS[key]);
  }
  close(){this.closed=true;this.ready=false;if(this.socket)this.socket.terminate();}
}
module.exports={Samsung,request,KEYS};
