(() => {
'use strict';
const root=document.getElementById('game-root');if(!root)return;
const KEY='greetconsole.foodsim.v1',TARGET=8;
const P={
 burger:{n:'Чизбургер',e:'🍔',p:189,c:64,s:{beef:1,bun:1,cheese:1},r:[['Обжарить котлету','Гриль',4],['Собрать чизбургер','Сборка',2]]},
 big:{n:'Биг Хит',e:'🍔',p:289,c:108,s:{beef:1,bun:1,cheese:1},r:[['Приготовить котлету','Гриль',5],['Собрать бургер','Сборка',3]]},
 fries:{n:'Картофель фри',e:'🍟',p:119,c:36,s:{potato:1},r:[['Обжарить картофель','Фритюр',5],['Посолить и упаковать','Сборка',2]]},
 nuggets:{n:'Наггетсы · 6 шт.',e:'🍗',p:169,c:68,s:{chicken:1},r:[['Обжарить наггетсы','Фритюр',5],['Упаковать порцию','Сборка',2]]},
 drink:{n:'Газированный напиток',e:'🥤',p:99,c:22,s:{cup:1},r:[['Наполнить стакан','Напитки',3],['Закрыть крышкой','Выдача',1]]}
};
const stock=[['beef','Котлеты','🥩'],['bun','Булочки','🍞'],['cheese','Сыр','🧀'],['potato','Картофель','🥔'],['chicken','Курица','🍗'],['cup','Стаканы','🥤']];
const suppliers=[
{id:'burger',n:'Набор для бургеров',d:'10 котлет · 10 булочек · 10 сыра',p:920,a:{beef:10,bun:10,cheese:10}},
{id:'potato',n:'Картофель',d:'10 порций',p:310,a:{potato:10}},
{id:'chicken',n:'Курица',d:'10 порций',p:590,a:{chicken:10}},
{id:'cups',n:'Стаканы',d:'10 штук',p:190,a:{cup:10}}
];
const upgrades=[
{id:'grill',n:'Профессиональный гриль',d:'Котлеты готовятся быстрее',p:1550,m:2},
{id:'fryer',n:'Мощная фритюрница',d:'Ускоряет картофель и наггетсы',p:1350,m:2},
{id:'cashier',n:'Вторая касса',d:'Ещё один заказ в очереди',p:2200,m:1}
];
const names=['Артём','Полина','Максим','Алиса','Даниил','София','Илья','Варвара','Марк','Ева','Тимур','Мия'];
const quotes=['Можно побыстрее, пожалуйста?','Возьму что-нибудь вкусное.','Заказ с собой, спасибо!','Сегодня я с хорошим аппетитом.','Пожалуйста, всё свежее и горячее.'];
const combos=[['burger','fries','drink'],['big','fries','drink'],['nuggets','fries','drink'],['burger','nuggets'],['big','nuggets','drink'],['burger','fries']];
function initial(){return{day:1,money:5000,rating:4.6,totalGuests:0,next:1,selected:null,stock:{beef:10,bun:10,cheese:10,potato:10,chicken:10,cup:12},up:{grill:0,fryer:0,cashier:0},shift:{served:0,sales:0,costs:0,profit:0,closed:false},orders:[],notice:'Смена открыта. Пригласи первого гостя.'};}
function load(){try{let x=JSON.parse(localStorage.getItem(KEY)||'null');if(!x||!x.stock||!x.up||!x.shift||!Array.isArray(x.orders))return initial();let b=initial(),s=Object.assign(b,x);s.stock=Object.assign(b.stock,x.stock);s.up=Object.assign(b.up,x.up);s.shift=Object.assign(b.shift,x.shift);s.orders.forEach(o=>o.items.forEach(i=>{if(i.status==='cooking')i.status='paused';i.left=Math.max(0,+i.left||0);i.total=Math.max(1,+i.total||1);}));if(!s.orders.some(o=>o.id===s.selected))s.selected=s.orders.length?s.orders[0].id:null;return s;}catch{return initial();}}
let S=load(),last=Date.now(),lastSave=0;
function save(){try{localStorage.setItem(KEY,JSON.stringify(S));}catch{S.notice='Не удалось сохранить прогресс в браузере.';}}
function rub(n){return Math.round(n).toLocaleString('ru-RU')+' ₽';}
function esc(v){return String(v).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
function pick(a){return a[Math.floor(Math.random()*a.length)];}
function cap(){return 3+(S.up.cashier||0);}
function selected(){return S.orders.find(o=>o.id===S.selected)||null;}
function ready(o){return !!o&&o.items.every(i=>i.status==='done');}
function secs(step){return Math.max(1,step[2]-(step[1]==='Гриль'?S.up.grill:step[1]==='Фритюр'?S.up.fryer:0));}
function invite(){if(S.shift.closed)return;if(S.shift.served>=TARGET){S.notice='План выполнен. Разбери очередь и закрывай смену.';return render();}if(S.orders.length>=cap()){S.notice='Очередь заполнена. Сначала выдай заказ.';return render();}let id=S.next++;S.orders.push({id,guest:pick(names),quote:pick(quotes),items:pick(combos).map(pid=>({pid,step:0,status:'idle',reserved:false,left:0,total:1}))});S.selected=id;S.notice='Гость у кассы. Разные позиции можно готовить параллельно.';save();render();}
function has(p){return Object.entries(p.s).every(([k,n])=>(S.stock[k]||0)>=n);}
function missing(p){return Object.keys(p.s).filter(k=>(S.stock[k]||0)<p.s[k]).map(k=>stock.find(x=>x[0]===k)[1]);}
function start(id,n){let o=S.orders.find(x=>x.id===id),i=o&&o.items[n];if(!i)return;if(i.status==='paused'){i.status='cooking';last=Date.now();S.notice='Готовка продолжена.';save();return render();}if(i.status!=='idle')return;let p=P[i.pid];if(!i.reserved){if(!has(p)){S.notice='Не хватает: '+missing(p).join(', ')+'. Закажи поставку со склада.';return render();}Object.entries(p.s).forEach(([k,v])=>S.stock[k]-=v);S.money-=p.c;S.shift.costs+=p.c;i.reserved=true;}let ms=secs(p.r[i.step])*1000;i.left=ms;i.total=ms;i.status='cooking';last=Date.now();S.notice=p.r[i.step][1]+': '+p.r[i.step][0]+'.';save();render();}
function collect(id,n){let o=S.orders.find(x=>x.id===id),i=o&&o.items[n];if(!i||i.status!=='ready')return;i.step++;i.left=0;if(i.step>=P[i.pid].r.length){i.status='done';S.notice=P[i.pid].n+' готово.';}else{i.status='idle';S.notice='Этап выполнен. Далее: '+P[i.pid].r[i.step][0].toLowerCase()+'.';}save();render();}
function serve(id){let at=S.orders.findIndex(o=>o.id===id),o=S.orders[at];if(!o||!ready(o))return;let rev=o.items.reduce((a,i)=>a+P[i.pid].p,0),cost=o.items.reduce((a,i)=>a+P[i.pid].c,0);S.money+=rev;S.shift.served++;S.totalGuests++;S.shift.sales+=rev;S.shift.profit+=rev-cost;S.rating=Math.min(5,Math.round((S.rating+.025)*100)/100);S.orders.splice(at,1);S.selected=S.orders.length?S.orders[Math.min(at,S.orders.length-1)].id:null;S.notice='Заказ выдан! Выручка '+rub(rev)+', маржа '+rub(rev-cost)+'.';if(S.shift.served>=TARGET)S.notice+=' План смены выполнен.';save();render();}
function restock(id){let s=suppliers.find(x=>x.id===id);if(!s)return;if(S.money<s.p){S.notice='Не хватает денег на поставку.';return render();}S.money-=s.p;S.shift.costs+=s.p;S.shift.profit-=s.p;Object.entries(s.a).forEach(([k,v])=>S.stock[k]=(S.stock[k]||0)+v);S.notice='Поставка принята: '+s.n+'.';save();render();}
function upgrade(id){let u=upgrades.find(x=>x.id===id);if(!u)return;let lvl=S.up[id]||0;if(lvl>=u.m)return;let price=u.p*(lvl+1);if(S.money<price){S.notice='Не хватает денег на улучшение.';return render();}S.money-=price;S.shift.costs+=price;S.shift.profit-=price;S.up[id]=lvl+1;S.notice='Установлено: '+u.n+'.';save();render();}
function closeShift(){if(S.shift.closed||S.shift.served<TARGET||S.orders.length)return;let bonus=500+Math.max(0,Math.round(S.shift.profit*.1));S.money+=bonus;S.shift.closed=true;S.notice='Смена закрыта. Премия: '+rub(bonus)+'.';save();render();}
function nextShift(){if(!S.shift.closed)return;S.day++;S.shift={served:0,sales:0,costs:0,profit:0,closed:false};S.orders=[];S.selected=null;S.notice='День '+S.day+'. Новая смена открыта.';save();render();}
function pause(){let changed=false;S.orders.forEach(o=>o.items.forEach(i=>{if(i.status==='cooking'){i.status='paused';changed=true;}}));if(changed)save();}
function tick(){
 if(document.hidden)return;
 const now=Date.now(),dt=Math.min(1000,Math.max(0,now-last));last=now;
 let becameReady=false,active=false;
 S.orders.forEach(o=>o.items.forEach((i,index)=>{
  if(i.status!=='cooking')return;
  active=true;i.left=Math.max(0,i.left-dt);
  if(i.left===0){i.status='ready';S.notice=P[i.pid].n+': этап готов, забирай.';becameReady=true;return;}
  const key=o.id+':'+index;
  const timer=root.querySelector('[data-timer="'+key+'"]');
  const bar=root.querySelector('[data-progress="'+key+'"]');
  if(timer)timer.textContent='Готовится · '+Math.ceil(i.left/1000)+' сек';
  if(bar)bar.style.width=Math.max(0,Math.min(100,100*(1-i.left/i.total)))+'%';
 }));
 // Не пересоздаём DOM каждые 250 мс: кнопки готовки остаются теми же элементами
 // и не теряют клик, пока обновляется таймер.
 if(becameReady)render();
 if(now-lastSave>900){save();lastSave=now;}
}
function itemHtml(o,i,n){let p=P[i.pid],step=p.r[Math.min(i.step,p.r.length-1)],label='Этап '+(i.step+1)+' из '+p.r.length,btn='Начать · '+secs(step)+' сек',act='start',cls='',off='',pct=0;if(i.status==='cooking'){label='Готовится · '+Math.ceil(i.left/1000)+' сек';btn='Готовится…';off=' disabled';cls=' is-running';pct=100*(1-i.left/i.total);}else if(i.status==='paused'){label='Пауза · '+Math.ceil(i.left/1000)+' сек';btn='Продолжить';cls=' is-resume';}else if(i.status==='ready'){label='Этап готов';btn='Забрать';act='collect';cls=' is-ready';pct=100;}else if(i.status==='done'){label='Готово ✓';btn='Готово';off=' disabled';cls=' is-done';pct=100;}return '<article class="sim-product '+i.status+'"><div class="sim-product-icon">'+p.e+'</div><div class="sim-product-body"><div class="sim-product-heading"><strong>'+p.n+'</strong><span>'+rub(p.p)+'</span></div><div class="sim-product-step">'+(i.status==='done'?'Можно выдавать гостю':step[1]+' · '+step[0])+'</div><div class="sim-progress-track"><i data-progress="'+o.id+':'+n+'" style="width:'+Math.max(0,pct)+'%"></i></div><div class="sim-product-footer"><small data-timer="'+o.id+':'+n+'">'+label+'</small><button class="sim-action'+cls+'" data-action="'+act+'" data-order="'+o.id+'" data-item="'+n+'"'+off+'>'+btn+'</button></div></div></article>';}
function ticket(o){let total=o.items.reduce((a,i)=>a+P[i.pid].p,0),done=o.items.filter(i=>i.status==='done').length;return '<button class="sim-ticket'+(o.id===S.selected?' selected':'')+'" data-action="select" data-order="'+o.id+'"><span class="sim-ticket-top"><b>ЗАКАЗ №'+String(o.id).padStart(3,'0')+'</b><em>'+done+'/'+o.items.length+'</em></span><strong>'+esc(o.guest)+'</strong><span class="sim-ticket-items">'+o.items.map(i=>P[i.pid].n).join(' · ')+'</span><span class="sim-ticket-bottom"><small>'+(ready(o)?'Можно выдавать':'В работе')+'</small><b>'+rub(total)+'</b></span></button>';}
function render(){
let o=selected(),pct=Math.min(100,S.shift.served/TARGET*100),capacity=cap(),canInvite=!S.shift.closed&&S.shift.served<TARGET&&S.orders.length<capacity;
let stockHtml=stock.map(x=>'<div class="sim-stock-item"><span>'+x[2]+' '+x[1]+'</span><b>'+S.stock[x[0]]+'</b></div>').join('');
let tickets=S.orders.length?S.orders.map(ticket).join(''):'<div class="sim-empty"><span>🧾</span><b>Пока тихо</b><small>Пригласи гостя, чтобы принять первый заказ.</small></div>';
let kitchen='<div class="sim-no-order"><div>👨‍🍳</div><b>Кухня ждёт заказ</b><p>Выбери заказ слева. Позиции готовятся независимо друг от друга.</p></div>';
if(o){let ok=ready(o);kitchen='<div class="sim-order-heading"><div><small>ЗАКАЗ №'+String(o.id).padStart(3,'0')+' · '+esc(o.guest)+'</small><h3>Что готовим?</h3></div><span class="sim-status-pill'+(ok?' ready':'')+'">'+(ok?'Готов к выдаче':'На кухне')+'</span></div><p class="sim-customer-phrase">«'+esc(o.quote)+'»</p><div class="sim-products">'+o.items.map((i,n)=>itemHtml(o,i,n)).join('')+'</div><button class="sim-serve" data-action="serve" data-order="'+o.id+'"'+(ok?'':' disabled')+'>'+(ok?'✓ Выдать заказ гостю':'Приготовь все позиции для выдачи')+'</button>';}
let supply=suppliers.map(s=>'<button class="sim-buy-row" data-action="stock" data-id="'+s.id+'"><span><b>'+s.n+'</b><small>'+s.d+'</small></span><strong>'+rub(s.p)+'</strong></button>').join('');
let ups=upgrades.map(u=>{let lvl=S.up[u.id]||0,max=lvl>=u.m;return '<div class="sim-upgrade"><div class="sim-upgrade-info"><b>'+u.n+'</b><small>'+u.d+'</small><span class="sim-level">Уровень '+lvl+'/'+u.m+'</span></div><button data-action="upgrade" data-id="'+u.id+'"'+(max?' disabled':'')+'>'+(max?'MAX':rub(u.p*(lvl+1)))+'</button></div>';}).join('');
let close=S.shift.served>=TARGET&&S.orders.length===0&&!S.shift.closed,shiftAction='<div class="sim-shift-hint">Обслужи '+TARGET+' гостей и разбери очередь.</div>';if(close)shiftAction='<button class="sim-close-shift" data-action="close">Закрыть смену и получить премию</button>';if(S.shift.closed)shiftAction='<button class="sim-close-shift" data-action="next">Начать день '+(S.day+1)+'</button>';
root.innerHTML='<div class="sim-shell"><section class="sim-hero"><div class="sim-logo">ВТ<span>•</span></div><div class="sim-hero-title"><span>ВКУСНО — И ТОЧКА</span><small>СИМУЛЯТОР РЕСТОРАНА</small></div><div class="sim-location"><b>ТОЧКА №014</b><span>ДЕНЬ '+S.day+' · '+(S.shift.closed?'СМЕНА ЗАКРЫТА':'СМЕНА ОТКРЫТА')+'</span></div><button class="sim-ghost" data-action="reset" title="Сбросить прогресс">↻</button></section>'+
'<section class="sim-stats"><div class="sim-stat"><small>ДЕНЬГИ В КАССЕ</small><b>'+rub(S.money)+'</b><span>На закупки и развитие</span></div><div class="sim-stat"><small>ГОСТИ ЗА СМЕНУ</small><b>'+S.shift.served+'<i> / '+TARGET+'</i></b><span>Всего: '+S.totalGuests+'</span></div><div class="sim-stat"><small>РЕЙТИНГ ТОЧКИ</small><b>★ '+S.rating.toFixed(2).replace('.',',')+'</b><span>Максимум 5,00</span></div><div class="sim-stat"><small>ПРИБЫЛЬ СМЕНЫ</small><b class="'+(S.shift.profit>=0?'positive':'negative')+'">'+rub(S.shift.profit)+'</b><span>С учётом закупок</span></div></section>'+
'<section class="sim-shift-progress"><div><b>План смены</b><span>'+S.shift.served+' из '+TARGET+' гостей</span></div><div class="sim-big-track"><i style="width:'+pct+'%"></i></div></section>'+
'<div class="sim-workspace"><section class="sim-panel sim-queue"><div class="sim-panel-heading"><div><small>ЗАЛ РЕСТОРАНА</small><h3>Очередь заказов</h3></div><span>'+S.orders.length+'/'+capacity+'</span></div><button class="sim-invite" data-action="invite"'+(canInvite?'':' disabled')+'>＋ Пригласить гостя</button><div class="sim-ticket-list">'+tickets+'</div><div class="sim-queue-note">Гости не торопят: готовь в своём темпе и запускай несколько позиций параллельно.</div></section>'+
'<section class="sim-panel sim-kitchen"><div class="sim-panel-heading"><div><small>РАБОЧАЯ ЗОНА</small><h3>Кухня и выдача</h3></div><span class="sim-live-dot">● РАБОТАЕТ</span></div>'+kitchen+'</section></div>'+
'<div class="sim-management"><section class="sim-panel"><div class="sim-panel-heading"><div><small>СКЛАД</small><h3>Остатки продуктов</h3></div></div><div class="sim-stock-grid">'+stockHtml+'</div><div class="sim-subheading">Заказать поставку</div><div class="sim-buy-list">'+supply+'</div></section>'+
'<section class="sim-panel"><div class="sim-panel-heading"><div><small>РАЗВИТИЕ</small><h3>Улучшение кухни</h3></div></div><div class="sim-upgrade-list">'+ups+'</div></section></div>'+
'<section class="sim-shift-summary"><div><b>Итоги текущей смены</b><span>Выручка '+rub(S.shift.sales)+' · Расходы '+rub(S.shift.costs)+'</span></div>'+shiftAction+'</section>'+
'<div class="sim-notice" role="status">'+esc(S.notice||'')+'</div><div class="sim-disclaimer">Фанатская мини-игра для GreetConsole. Прогресс хранится в данных расширения на этом устройстве.</div></div>';
}
root.addEventListener('click',e=>{let b=e.target.closest('[data-action]');if(!b||b.disabled)return;let a=b.dataset.action,id=Number(b.dataset.order),n=Number(b.dataset.item);if(a==='invite')invite();else if(a==='select'){S.selected=id;save();render();}else if(a==='start')start(id,n);else if(a==='collect')collect(id,n);else if(a==='serve')serve(id);else if(a==='stock')restock(b.dataset.id);else if(a==='upgrade')upgrade(b.dataset.id);else if(a==='close')closeShift();else if(a==='next')nextShift();else if(a==='reset'&&confirm('Точно сбросить весь прогресс симулятора? Это нельзя отменить.')){pause();S=initial();save();render();}});
document.addEventListener('visibilitychange',()=>{if(document.hidden)pause();else{last=Date.now();render();}});window.addEventListener('pagehide',pause);window.addEventListener('beforeunload',pause);render();setInterval(tick,250);
})();