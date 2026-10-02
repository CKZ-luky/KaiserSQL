import {registerPlugin} from '@capacitor/core';

// Both supplied pictures are bundled unchanged. Motion is drawn with CSS.
export function createStartup(Lab,native){
  const screen=document.querySelector('#startup'),workspace=document.querySelector('#workspace');
  const label=document.querySelector('#startup-status'),began=performance.now();
  const bars=registerPlugin('SystemBars');
  let timer,closing=false,appearance=Promise.resolve();
  function colors(startup){
    document.querySelector('meta[name=theme-color]').content=startup?'#fffdf4':'#102f29';
    if(!native)return;
    // SystemBars restores the theme background; set our phase color afterwards.
    appearance=appearance.catch(()=>{}).then(async()=>{
      await bars.setStyle({style:startup?'LIGHT':'DARK'});
      await Lab.startupAppearance({startup});
    }).catch(()=>{});
  }
  function dismiss(){
    if(closing||screen.hidden)return;
    closing=true;clearTimeout(timer);screen.classList.add('leaving');
    const delay=matchMedia('(prefers-reduced-motion: reduce)').matches?0:220;
    setTimeout(()=>{
      screen.hidden=true;workspace.inert=false;document.body.classList.remove('starting');
      document.querySelector('#startup-skip').blur();colors(false);
    },delay);
  }
  colors(true);
  document.querySelector('#startup-skip').onclick=dismiss;
  return {
    update(message){label.textContent=message;},
    finish(ok=true){
      if(screen.hidden||closing)return;
      label.textContent=ok?'实验室已就绪，开始你的探索':'进入工具箱查看引擎状态';
      screen.classList.add('prepared');
      timer=setTimeout(dismiss,Math.max(0,1600-(performance.now()-began)));
    }
  };
}
