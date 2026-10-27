'use strict';
const http=require('http'),https=require('https'),crypto=require('crypto'),fs=require('fs'),path=require('path');
const WebSocket=require('ws');
const {atomic,Failure}=require('./core.cjs');
function equal(a,b){const x=Buffer.from(String(a||'')),y=Buffer.from(String(b||''));return x.length===y.length&&crypto.timingSafeEqual(x,y);}
function identity(directory){
  const file=path.join(directory,'https-identity.json');
  if(fs.existsSync(file))return JSON.parse(fs.readFileSync(file,'utf8'));
  const forge=require('node-forge'),pki=forge.pki;
  const keys=pki.rsa.generateKeyPair({bits:2048,e:65537});
  const cert=pki.createCertificate();cert.publicKey=keys.publicKey;cert.serialNumber='01'+crypto.randomBytes(15).toString('hex');
  cert.validity.notBefore=new Date(Date.now()-86400000);cert.validity.notAfter=new Date(Date.now()+5*365*86400000);
  const attrs=[{name:'commonName',value:'MQTTV Tizen'}];cert.setSubject(attrs);cert.setIssuer(attrs);
  cert.setExtensions([{name:'basicConstraints',cA:false},{name:'keyUsage',digitalSignature:true,keyEncipherment:true},{name:'extKeyUsage',serverAuth:true},
    {name:'subjectAltName',altNames:[{type:2,value:'localhost'},{type:7,ip:'127.0.0.1'}]}]);
  cert.sign(keys.privateKey,forge.md.sha256.create());
  const value={privateKey:pki.privateKeyToPem(keys.privateKey),certificate:pki.certificateToPem(cert),publicKey:pki.publicKeyToPem(keys.publicKey)};
  atomic(file,value);return value;
}
function publicIdentity(i){
  const forge=require('node-forge');
  const der=forge.asn1.toDer(forge.pki.certificateToAsn1(forge.pki.certificateFromPem(i.certificate))).getBytes();
  const sha=crypto.createHash('sha256').update(Buffer.from(der,'binary')).digest('hex');
  const cert=forge.pki.certificateFromPem(i.certificate);
  const publicDer=forge.asn1.toDer(forge.pki.publicKeyToAsn1(cert.publicKey)).getBytes();
  return {algorithm:'RSA',public_key_pem:i.publicKey,public_key_sha256:crypto.createHash('sha256').update(Buffer.from(publicDer,'binary')).digest('hex'),certificate_pem:i.certificate,certificate_sha256:sha,not_before:cert.validity.notBefore.toISOString(),not_after:cert.validity.notAfter.toISOString(),storage:'app_private',persistent:true,platform:'tizen',private_key_exportable:true};
}
function body(req){
  return new Promise((resolve,reject)=>{
    let size=0;const chunks=[];
    req.on('data',b=>{size+=b.length;if(size>16384){reject(new Failure('payload_too_large','Request exceeds 16 KiB'));req.destroy();}else chunks.push(b);});
    req.on('end',()=>resolve(Buffer.concat(chunks)));req.on('error',reject);
  });
}
function reply(res,status,value){
  if(res.writableEnded)return;
  res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});
  res.end(JSON.stringify(value));
}
class Api {
  constructor(config,directory,processor,state,settings){this.settings=settings;this.c=config;this.directory=directory;this.processor=processor;this.state=state;this.servers=[];this.peers=new Set();this.sockets=new Set();this.tls=null;}
  async start(){
    for(const kind of ['http','https']){
      if(!this.c[kind].enabled)continue;
      const handler=(req,res)=>this.handle(req,res).catch(e=>reply(res,e.code==='payload_too_large'?413:['invalid_config','invalid_value','invalid_json'].includes(e.code)?400:e.code==='busy'?503:500,{error:{code:e.code||'api_error',message:e instanceof Failure?e.message:'API request failed'}}));
      if(kind==='https')this.tls=identity(this.directory);
      const server=kind==='https'?https.createServer({key:this.tls.privateKey,cert:this.tls.certificate,minVersion:'TLSv1.2'},handler):http.createServer(handler);
      const wss=new WebSocket.Server({noServer:true,maxPayload:16384,perMessageDeflate:false});
      server.on('connection',socket=>{
        if(this.sockets.size>=this.c.maxConnections){socket.destroy();return;}
        this.sockets.add(socket);socket.on('close',()=>this.sockets.delete(socket));
      });
      server.on('upgrade',(req,socket,head)=>{
        if(req.url!=='/api/ws'||!equal(req.headers['x-api-key'],this.c.passkey)||this.peers.size>=this.c.maxWebSockets){
          socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');return;
        }
        wss.handleUpgrade(req,socket,head,ws=>{
          ws.alive=true;this.peers.add(ws);
          ws.on('pong',()=>{ws.alive=true;});
          ws.on('close',()=>this.peers.delete(ws));ws.on('error',()=>{});
          ws.on('message',raw=>this.processor.submit(raw,false,false).then(ack=>this.send(ws,{ack}),e=>this.send(ws,{error:{code:e.code||'command_failed',message:'Command could not be completed'}})));
          this.state().then(state=>this.send(ws,{state}),()=>this.send(ws,{error:{code:'state_unavailable',message:'State unavailable'}}));
        });
      });
      server.setTimeout(this.c.requestTimeoutMs);
      this.servers.push(server);await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(this.c[kind].port,this.c.bindAddress,resolve);});
    }
    this.ping=setInterval(()=>{for(const ws of this.peers){if(!ws.alive){ws.terminate();continue;}ws.alive=false;ws.ping();}},20000);
  }
  send(ws,value){if(ws.readyState===WebSocket.OPEN){if(ws.bufferedAmount>262144){ws.terminate();return;}ws.send(JSON.stringify(value));}}
  broadcast(state){for(const ws of this.peers)this.send(ws,{state});}
  async handle(req,res){
    const assets={'/settings':'index.html','/settings/':'index.html','/app.js':'app.js','/style.css':'style.css','/settings/app.js':'app.js','/settings/style.css':'style.css'};
    if(req.method==='GET'&&this.settings&&assets[req.url]){
      const file=assets[req.url],data=fs.readFileSync(path.join(this.settings.assets,file));
      res.writeHead(200,{'Content-Type':file.endsWith('.html')?'text/html; charset=utf-8':file.endsWith('.css')?'text/css; charset=utf-8':'application/javascript; charset=utf-8','Cache-Control':'no-store','Content-Security-Policy':"default-src 'self'; connect-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'",'X-Content-Type-Options':'nosniff'});return res.end(data);
    }
    if(!equal(req.headers['x-api-key'],this.c.passkey))return reply(res,401,{error:{code:'unauthorized',message:'X-API-Key required'}});
    if(this.settings&&req.url==='/api/settings'){
      if(req.method==='GET')return reply(res,200,{config:this.settings.read(),running:true,state:await this.state()});
      if(req.method==='POST'){
        let raw;try{raw=JSON.parse((await body(req)).toString());}catch(_){throw new Failure('invalid_json','Invalid configuration JSON');}
        const config=this.settings.save(raw);res.once('finish',()=>setTimeout(()=>this.settings.reload(),50));return reply(res,200,{saved:true,config});
      }
    }
    if(this.settings&&req.method==='POST'&&req.url==='/api/pair'){await this.settings.pair();return reply(res,200,{paired:true});}
    if(req.method==='GET'&&req.url==='/api/state')return reply(res,200,{state:await this.state()});
    if(req.method==='POST'&&req.url==='/api/command'){
      const raw=await body(req);let timer;
      try{
        const ack=await Promise.race([this.processor.submit(raw,false,false),new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Failure('command_timeout','Execution is uncertain; retry the same command ID')),this.c.requestTimeoutMs);})]);
        return reply(res,200,ack);
      }finally{clearTimeout(timer);}
    }
    if(req.method==='GET'&&req.url==='/api/public-key'){
      if(!this.tls)return reply(res,404,{error:{code:'https_disabled',message:'HTTPS identity is not active'}});
      return reply(res,200,publicIdentity(this.tls));
    }
    if(req.method==='GET'&&req.url==='/api/certificate'&&this.tls){
      res.writeHead(200,{'Content-Type':'application/x-pem-file','Cache-Control':'no-store'});return res.end(this.tls.certificate);
    }
    reply(res,404,{error:{code:'not_found',message:'Unknown endpoint'}});
  }
  async stop(){
    clearInterval(this.ping);for(const ws of this.peers)ws.terminate();this.peers.clear();
    for(const socket of this.sockets)socket.destroy();this.sockets.clear();
    await Promise.all(this.servers.map(s=>new Promise(resolve=>s.close(resolve))));this.servers=[];
  }
}
module.exports={Api,equal,body,reply,identity,publicIdentity};
