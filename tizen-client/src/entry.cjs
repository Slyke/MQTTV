'use strict';
const {createService}=require('./runtime.cjs');
let service=null;
function start(){if(service)return;const next=createService();service=next;next.start().catch(()=>{console.error('MQTTV service failed to start');next.stop().catch(()=>{});service=null;});}
function stop(){const current=service;service=null;if(current)current.stop().catch(()=>{});}
module.exports.onStart=start;
module.exports.onRequest=start;
module.exports.onStop=stop;
module.exports.onExit=stop;
