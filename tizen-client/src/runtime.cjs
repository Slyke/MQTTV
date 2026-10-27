'use strict';
const fs=require('fs'),path=require('path'),os=require('os'),crypto=require('crypto'),http=require('http');
const mqtt=require('mqtt'),tlsModule=require('tls');
const {ensureDir,atomic,normalize,Processor,Failure}=require('./core.cjs');
const {Samsung}=require('./samsung.cjs');
const {Api,equal,body,reply}=require('./api.cjs');
class Runtime {
  constructor(config,directory,settings){
    this.config=config;this.directory=directory;this.current=null;this.online=false;this.mqtt=null;this.lastPublish=0;this.lastContent='';this.stopped=false;this.flushing=false;this.polling=null;
    this.driver=new Samsung(config.tv,()=>atomic(path.join(directory,'config.json'),this.config));
    this.processor=new Processor(path.join(directory,'commands.json'),config.service,async cmd=>{
      const result=await this.driver.execute(cmd,()=>this.snapshot());
      if(cmd.action!=='status'&&cmd.action!=='status_refresh')setTimeout(()=>this.poll(),0);
      return result;
    });
    this.api=new Api(config.api,directory,this.processor,()=>this.snapshot(),settings);
  }
  async snapshot(){
    const c=this.config,now=new Date(),heartbeat=c.service.stateHeartbeatSeconds,stale=heartbeat?Math.max(heartbeat*3,c.service.statePollSeconds*3):0;
    let tv;try{tv=await this.driver.snapshot();}catch(e){tv={reachable:false,power:'unknown',state:'unavailable',driver:'samsung-tizen',tv_error:e.code||'tv_unreachable'};}
    const interfaces={},ips={};let primary=null,active=null;
    const native=os.networkInterfaces();
    Object.keys(native).forEach(name=>{
      const values=native[name];ips[name]=values.map(x=>x.address);
      interfaces[name]={addresses:values.map(x=>({address:x.address,family:x.family.toLowerCase(),prefix_length:x.cidr?Number(x.cidr.split('/')[1]):null})),loopback:values.every(x=>x.internal)};
      const v4=values.find(x=>!x.internal&&x.family==='IPv4');
      if(v4&&!primary){primary=v4.address;active=name;}
    });
    const total=os.totalmem(),free=os.freemem();
    return Object.assign({
      schema_version:2,device_id:c.mqtt.clientId,timestamp:now.toISOString(),expires_at:stale?new Date(now.getTime()+stale*1000).toISOString():null,stale_after_seconds:stale,
      memory:{available_bytes:free,total_bytes:total,available_mb:Math.round(free/1048576),total_mb:Math.round(total/1048576),measurement:'os_free_memory'},
      network:{primary_ip:primary,active_interface:active,ips,interfaces,connected:Boolean(primary)},
      uptime_seconds:Math.floor(os.uptime()),
      system:{manufacturer:'Samsung',os:'Tizen',architecture:os.arch(),cpu_cores:os.cpus().length},
      application:{version:typeof __MQTTV_VERSION__==='string'?__MQTTV_VERSION__:require('../package.json').version,platform:'tizen',runtime:process.version,uptime_seconds:Math.floor(process.uptime()),pid:process.pid},
      mqtt:{base_topic:c.mqtt.effectiveBaseTopic},
      api:{ready:this.api.servers.length>0,http_enabled:c.api.http.enabled,https_enabled:c.api.https.enabled},
      lifecycle:{start_on_boot:Boolean(process.env.MQTTV_SERVICE_BOOT),method:process.env.MQTTV_SERVICE_BOOT||'manual',note:process.env.MQTTV_SERVICE_BOOT?'Managed by the TV service manager':'Open MQTTV after a cold boot'}
    },tv);
  }
  async start(){
    try{await this.api.start();}catch(e){await this.api.stop();throw e;}
    await this.poll();
    if(this.config.mqtt.uri)this.connectMqtt();
    this.timer=setInterval(()=>this.poll(),this.config.service.statePollSeconds*1000);
    this.driver.connect().catch(()=>{});
  }
  availability(online,reason){
    const s=this.current;
    return {schema_version:2,device_id:this.config.mqtt.clientId,online,status:online?'Online':'Offline',reason,timestamp:new Date().toISOString(),
      last_update:s?s.timestamp:null,last_ips:s?s.network.ips:{},primary_ip:s?s.network.primary_ip:null,active_interface:s?s.network.active_interface:null,stale_after_seconds:s?s.stale_after_seconds:0};
  }
  connectMqtt(){
    const c=this.config.mqtt,base=c.effectiveBaseTopic,uri=c.uri.replace(/^(tls|ssl):/,'mqtts:').replace(/^tcp:/,'mqtt:');
    const will=Object.assign(this.availability(false,'connection_lost'),{timestamp:null,metadata_scope:'will_registration',will_registered_at:new Date().toISOString()});
    const tls=c.tls,options={clientId:c.clientId,username:c.username,password:c.password,clean:true,keepalive:c.keepAliveSeconds,connectTimeout:10000,reconnectPeriod:2000,
      queueQoSZero:false,resubscribe:false,rejectUnauthorized:!tls.insecureSkipVerify,will:{topic:base+'/tele/LWT',payload:JSON.stringify(will),qos:1,retain:true}};
    if(tls.caCertificatePem)options.ca=tls.caCertificatePem;
    else if(tls.caFile){if(path.basename(tls.caFile)!==tls.caFile)throw new Failure('invalid_config','CA file must be inside app-private storage');options.ca=fs.readFileSync(path.join(this.directory,tls.caFile));}
    if(options.ca&&tls.trustSystemCAs)options.ca=(tlsModule.rootCertificates||[]).concat([options.ca]);
    const client=this.mqtt=mqtt.connect(uri,options);
    client.on('reconnect',()=>{client.options.will.payload=JSON.stringify(Object.assign(this.availability(false,'connection_lost'),{timestamp:null,metadata_scope:'will_registration',will_registered_at:new Date().toISOString()}));});
    client.on('connect',()=>{
      if(this.stopped)return;
      client.subscribe(base+'/cmnd/COMMAND',{qos:1},error=>{
        if(error)return;
        this.online=true;
        if(!this.config.service.stateRetain)client.publish(base+'/tele/STATE','',{qos:1,retain:true});
        this.publishState();this.flush();
      });
    });
    client.on('message',(topic,payload,packet)=>{
      if(topic!==base+'/cmnd/COMMAND'||this.stopped)return;
      this.processor.submit(payload,Boolean(packet.retain),true).then(()=>this.flush(),()=>{this.fatal='Command journal is unavailable; service stopped';this.stop();});
    });
    client.on('error',()=>{this.online=false;});
    client.on('close',()=>{this.online=false;});
  }
  publish(topic,payload,retain){return new Promise((resolve,reject)=>{
    if(!this.mqtt||!this.online)return reject(new Error('MQTT offline'));
    this.mqtt.publish(this.config.mqtt.effectiveBaseTopic+topic,typeof payload==='string'?payload:JSON.stringify(payload),{qos:1,retain},e=>e?reject(e):resolve());
  });}
  publishState(){
    if(!this.current||!this.online)return;
    this.publish('/tele/STATE',this.current,this.config.service.stateRetain).then(()=>this.publish('/tele/LWT',this.availability(true,'connected'),true)).catch(()=>{});
    this.lastPublish=Date.now();
  }
  async flush(){
    if(this.flushing||!this.online||this.stopped)return;this.flushing=true;
    try{
      while(this.online&&!this.stopped&&this.processor.data.outbox.length){
        const item=this.processor.data.outbox[0];await this.publish('/stat/RESULT',item.ack,false);this.processor.delivered(item.token);
      }
    }catch(_){}finally{this.flushing=false;}
  }
  poll(){
    if(this.polling)return this.polling;
    this.polling=this.snapshot().then(state=>{
      this.current=state;
      const key=JSON.stringify({reachable:state.reachable,power:state.power,volume:state.volume,muted:state.muted,network:state.network.ips,control:state.control});
      const heartbeat=this.config.service.stateHeartbeatSeconds;
      if(key!==this.lastContent || (heartbeat&&Date.now()-this.lastPublish>=heartbeat*1000)){this.lastContent=key;this.publishState();this.api.broadcast(state);}
      this.flush();
    }).catch(()=>{}).then(()=>{this.polling=null;});
    return this.polling;
  }
  async stop(){
    this.stopped=true;clearInterval(this.timer);await this.processor.queue;
    this.driver.close();await this.api.stop();
    const client=this.mqtt;this.mqtt=null;
    if(client)await new Promise(resolve=>{
      const timer=setTimeout(()=>{client.end(true);resolve();},2000);
      const end=()=>client.end(false,()=>{clearTimeout(timer);resolve();});
      if(client.connected)client.publish(this.config.mqtt.effectiveBaseTopic+'/tele/LWT',JSON.stringify(this.availability(false,'stopped')),{qos:1,retain:true},end);else{client.end(true);clearTimeout(timer);resolve();}
    });
  }
}
function createService(options){
  const directory=options&&options.directory||process.env.TIZEN_APP_DATA_PATH||path.resolve(__dirname,'../../../data');
  const configFile=path.join(directory,'config.json'),keyFile=path.join(directory,'setup-key.txt');
  let runtime=null,bootstrap=null,transition=Promise.resolve(),lastError=null;
  ensureDir(directory);
  if(!fs.existsSync(keyFile))fs.writeFileSync(keyFile,crypto.randomBytes(32).toString('hex'),{mode:0o600});
  function load(){const raw=fs.existsSync(configFile)?JSON.parse(fs.readFileSync(configFile,'utf8')):{};const config=normalize(raw);atomic(configFile,config);return config;}
  const apply=enabled=>{
    transition=transition.catch(()=>{}).then(async()=>{
      if(runtime){await runtime.stop();runtime=null;}
      if(enabled){const next=new Runtime(load(),directory,{assets:process.env.MQTTV_UI_DIR||path.resolve(__dirname,'..'),read:load,save:raw=>{const next=normalize(raw);atomic(configFile,next);return next;},reload:()=>{apply(true).catch(()=>{});},pair:()=>{if(!runtime)throw new Failure('service_stopped','Start the service first');return runtime.driver.connect();}});try{await next.start();runtime=next;lastError=null;}catch(e){await next.stop();lastError='Service could not start; check listeners and configuration';throw e;}}
    });return transition;
  };
  return {
    start:async()=>{
      load();
      bootstrap=http.createServer(async(req,res)=>{
        if(req.method==='OPTIONS'){res.setHeader('Access-Control-Allow-Origin','*');res.setHeader('Access-Control-Allow-Headers','Content-Type,X-MQTTV-Setup');res.writeHead(204);return res.end();}
        if(!equal(req.headers['x-mqttv-setup'],fs.readFileSync(keyFile,'utf8').trim()))return reply(res,401,{error:{code:'unauthorized',message:'Local setup key required'}});
        // Web apps run from file URLs. The unguessable app-private key is required even on loopback.
        res.setHeader('Access-Control-Allow-Origin','*');res.setHeader('Access-Control-Allow-Headers','Content-Type,X-MQTTV-Setup');
        if(req.method==='OPTIONS'){res.writeHead(204);return res.end();}
        try{
          if(req.method==='GET'&&req.url==='/setup')return reply(res,200,{config:load(),running:Boolean(runtime),error:lastError,state:runtime?await runtime.snapshot():null});
          if(req.method==='POST'&&req.url==='/save'){
            let raw;try{raw=JSON.parse((await body(req)).toString());}catch(_){throw new Failure('invalid_json','Invalid configuration JSON');}
            const config=normalize(raw);atomic(configFile,config);if(runtime)await apply(true);return reply(res,200,{saved:true,config});
          }
          if(req.method==='POST'&&(req.url==='/start'||req.url==='/stop')){
            const enabled=req.url==='/start';atomic(path.join(directory,'enabled.json'),enabled);await apply(enabled);return reply(res,200,{running:Boolean(runtime)});
          }
          if(req.method==='POST'&&req.url==='/pair'){
            if(!runtime)await apply(true);await runtime.driver.connect();return reply(res,200,{paired:true});
          }
          return reply(res,404,{error:{code:'not_found',message:'Unknown local setup endpoint'}});
        }catch(e){reply(res,400,{error:{code:e.code||'settings_failed',message:e instanceof Failure?e.message:'Settings operation failed'}});}
      });
      bootstrap.setTimeout(60000);
      await new Promise((resolve,reject)=>{bootstrap.once('error',reject);bootstrap.listen(8790,'127.0.0.1',resolve);});
      if(fs.existsSync(path.join(directory,'enabled.json'))&&JSON.parse(fs.readFileSync(path.join(directory,'enabled.json'),'utf8')))await apply(true);
    },
    stop:async()=>{await apply(false);if(bootstrap)await new Promise(resolve=>bootstrap.close(resolve));}
  };
}
module.exports={Runtime,createService};
