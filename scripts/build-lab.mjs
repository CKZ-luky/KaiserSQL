import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const result=spawnSync(process.execPath,[path.join(root,'node_modules/vite/bin/vite.js'),'build','--config',path.join(root,'vite.lab.config.mjs')],{cwd:root,stdio:'inherit',windowsHide:true});
if(result.status!==0)process.exit(result.status||1);
const assets=path.join(root,'android/app/src/offline/assets/public');
if(!assets.startsWith(path.resolve(root)+path.sep))throw new Error('Invalid generated assets directory');
fs.rmSync(assets,{recursive:true,force:true});fs.mkdirSync(assets,{recursive:true});
// Only this generated UI directory is replaced; runtime assets and legacy UI stay separate.
fs.cpSync(path.join(root,'app/lab-dist'),assets,{recursive:true});
fs.copyFileSync(path.join(root,'licenses/mobile-dependencies.txt'),path.join(assets,'open-source-licenses.txt'));
if(fs.existsSync(path.join(root,'vendor/offline/licenses.txt')))fs.appendFileSync(path.join(assets,'open-source-licenses.txt'),fs.readFileSync(path.join(root,'vendor/offline/licenses.txt')));
const config=JSON.parse(fs.readFileSync(path.join(root,'capacitor.config.json'),'utf8'));
config.appId='com.pocketmysql.lab';config.appName='MySQL 离线实验室';
config.plugins.SystemBars={style:'LIGHT',initialViewportFitValueHint:'cover'};
fs.writeFileSync(path.join(root,'android/app/src/offline/assets/capacitor.config.json'),JSON.stringify(config,null,2));
console.log('KaiserSQL offline UI built.');
