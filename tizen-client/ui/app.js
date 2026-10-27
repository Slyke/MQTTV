'use strict';
(function(){
  var webMode=typeof tizen==='undefined'&&/^https?:$/.test(location.protocol);
  var state=null,key=null,base=webMode?location.origin:'http://127.0.0.1:8790',attempts=0;
  function el(id){return document.getElementById(id);}
  function status(message){el('status').textContent=message;}
  function request(route,method,value){
    var headers={'Content-Type':'application/json'};headers[webMode?'X-API-Key':'X-MQTTV-Setup']=key;
    if(webMode)route={'/setup':'/api/settings','/save':'/api/settings','/pair':'/api/pair'}[route]||route;
    return fetch(base+route,{method:method||'GET',headers:headers,
      body:value===undefined?undefined:JSON.stringify(value)}).then(function(r){return r.json().then(function(v){if(!r.ok)throw new Error(v.error&&v.error.message||'Settings request failed');return v;});});
  }
  function fill(config){
    state=JSON.parse(JSON.stringify(config));
    var map={'mqtt-uri':config.mqtt.uri,'mqtt-client':config.mqtt.clientId,'mqtt-topic':config.mqtt.baseTopic,'mqtt-user':config.mqtt.username,
      'mqtt-password':config.mqtt.password,'api-passkey':config.api.passkey,'api-bind':config.api.bindAddress,'api-http-port':config.api.http.port,'api-https-port':config.api.https.port,'tv-name':config.tv.name};
    Object.keys(map).forEach(function(id){el(id).value=map[id]||'';});
    el('mqtt-insecure').checked=Boolean(config.mqtt.tls.insecureSkipVerify);el('api-http').checked=Boolean(config.api.http.enabled);el('api-https').checked=Boolean(config.api.https.enabled);
  }
  function draft(){
    if(!state)throw new Error('Settings have not loaded');
    var c=JSON.parse(JSON.stringify(state));
    c.mqtt.uri=el('mqtt-uri').value.trim();c.mqtt.clientId=el('mqtt-client').value.trim();c.mqtt.baseTopic=el('mqtt-topic').value.trim();
    c.mqtt.username=el('mqtt-user').value;c.mqtt.password=el('mqtt-password').value;c.mqtt.tls.insecureSkipVerify=el('mqtt-insecure').checked;
    c.api.passkey=el('api-passkey').value;c.api.bindAddress=el('api-bind').value.trim();c.api.http.enabled=el('api-http').checked;c.api.http.port=Number(el('api-http-port').value);
    c.api.https.enabled=el('api-https').checked;c.api.https.port=Number(el('api-https-port').value);c.tv.name=el('tv-name').value.trim();return c;
  }
  function load(){return request('/setup').then(function(v){fill(v.config);status(v.error||(v.running?'Background service running':'Ready — configure connections, then Start'));});}
  function act(fn){Promise.resolve().then(fn).catch(function(e){status(e.message);});}
  el('settings').onsubmit=function(e){e.preventDefault();act(function(){status('Saving…');return request('/save','POST',draft()).then(function(v){fill(v.config);if(webMode){key=v.config.api.passkey;sessionStorage.setItem('mqttv-key',key);}status('Settings saved');});});};
  el('start').onclick=function(){act(function(){status('Starting…');return request('/start','POST').then(load);});};
  el('stop').onclick=function(){act(function(){return request('/stop','POST').then(load);});};
  el('pair').onclick=function(){act(function(){status('Allow MQTTV when the TV asks for network control');return request('/pair','POST').then(function(){status('Samsung local control paired');});});};
  el('leave').onclick=function(){try{tizen.application.getCurrentApplication().hide();}catch(e){status('Press Home to return to the TV');}};
  el('show-passwords').onchange=function(){['mqtt-password','api-passkey'].forEach(function(id){el(id).type=el('show-passwords').checked?'text':'password';});};
  el('load-json').onclick=function(){act(function(){fill(JSON.parse(el('json').value));status('JSON loaded; Save & apply to activate');});};
  el('export-json').onclick=function(){act(function(){el('json').value=JSON.stringify(draft(),null,2);});};
  document.addEventListener('keydown',function(e){
    if(e.keyCode===10009){try{tizen.application.getCurrentApplication().hide();}catch(_){}return;}
    if([37,38,39,40].indexOf(e.keyCode)<0||document.activeElement.tagName==='TEXTAREA')return;
    if(document.activeElement.tagName==='INPUT'&&document.activeElement.type!=='checkbox')return;
    var nodes=Array.prototype.slice.call(document.querySelectorAll('input,button,summary,textarea')).filter(function(n){return n.offsetParent!==null;});
    var current=document.activeElement.getBoundingClientRect(),cx=current.left+current.width/2,cy=current.top+current.height/2,best=null,score=Infinity;
    nodes.forEach(function(n){if(n===document.activeElement)return;var r=n.getBoundingClientRect(),dx=r.left+r.width/2-cx,dy=r.top+r.height/2-cy;
      if((e.keyCode===37&&dx>=-1)||(e.keyCode===39&&dx<=1)||(e.keyCode===38&&dy>=-1)||(e.keyCode===40&&dy<=1))return;
      var s=Math.abs(e.keyCode===37||e.keyCode===39?dx:dy)+3*Math.abs(e.keyCode===37||e.keyCode===39?dy:dx);
      if(s<score){score=s;best=n;}});
    if(best){e.preventDefault();best.focus();}
  });
  function readKey(){
    tizen.filesystem.resolve('wgt-private',function(dir){
      try{dir.resolve('setup-key.txt').readAsText(function(value){key=value.trim();load().then(function(){el('mqtt-uri').focus();},retry);},retry,'UTF-8');}catch(e){retry(e);}
    },retry,'r');
  }
  function retry(e){if(++attempts<30)setTimeout(readKey,1000);else status('Settings service unavailable: '+(e.message||e.name||'unknown error'));}
  if(webMode){
    ['start','stop','leave'].forEach(function(id){el(id).style.display='none';});
    var login=document.createElement('form');login.id='browser-login';login.innerHTML='<section><h2>Sign in to MQTTV</h2><label>API passkey<input id="browser-key" type="password" autocomplete="current-password"></label><button type="submit">Continue</button></section>';
    el('settings').before(login);el('settings').style.display='none';
    login.onsubmit=function(e){e.preventDefault();key=el('browser-key').value;act(function(){return load().then(function(){sessionStorage.setItem('mqttv-key',key);login.style.display='none';el('settings').style.display='grid';});});};
    status('Enter the API passkey to edit connections');el('browser-key').focus();return;
  }
  try{
    var control=new tizen.ApplicationControl('http://tizen.org/appcontrol/operation/service');
    tizen.application.launchAppControl(control,'MQTTVTizen.Service',function(){setTimeout(readKey,700);},function(e){status('Could not start settings service: '+e.name);});
  }catch(e){status('This build must run on a Samsung Tizen TV');}
})();
