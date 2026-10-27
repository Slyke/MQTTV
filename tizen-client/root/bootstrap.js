'use strict';
// Run with /usr/bin/node < bootstrap.js on the owner's TV.
var path=require('path');
var directory=process.env.MQTTV_HOME||'/opt/usr/home/owner/share/MQTTV';
process.env.TIZEN_APP_DATA_PATH=process.env.TIZEN_APP_DATA_PATH||path.join(directory,'data');
process.env.MQTTV_UI_DIR=path.join(directory,'ui');
var service=require(path.join(directory,'service/index.js'));
service.onStart();
function stop(){service.onStop();setTimeout(function(){process.exit(0);},6000).unref();}
process.on('SIGTERM',stop);process.on('SIGINT',stop);
