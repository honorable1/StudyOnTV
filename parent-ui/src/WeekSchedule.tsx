import {useEffect,useRef,useState} from 'react';
import {CalendarDays,RefreshCw} from 'lucide-react';
import {toast} from 'sonner';
import {Button} from '@/components/ui/button';
import {Switch} from '@/components/ui/switch';
import {api} from './api';

const DAYS=['周一','周二','周三','周四','周五','周六','周日'];
const EMPTY='0'.repeat(48);

function range(start:string,end:string){
 const chars=Array(48).fill('0');
 const min=(v:string)=>{const[h,m]=v.split(':').map(Number);return h*60+m;};
 const a=min(start),b=min(end);
 for(let i=0;i<48;i++){const t=i*30;if((a<b&&t>=a&&t<b)||(a>b&&(t>=a||t<b)))chars[i]='1';}
 return chars.join('');
}
function build(weekday:string|null,weekend:string|null){
 return DAYS.map((_,i)=>(i<5?weekday:weekend)||EMPTY);
}
const TEMPLATES:[string,()=>string[]][]=[
 ['上学日',()=>build(range('18:30','20:30'),range('09:00','20:00'))],
 ['每天',()=>build(range('18:30','20:30'),range('18:30','20:30'))],
 ['只在周末',()=>build(null,range('09:00','20:00'))],
 ['全天',()=>DAYS.map(()=> '1'.repeat(48))],
 ['清空',()=>DAYS.map(()=>EMPTY)]
];

export default function WeekSchedule({value,onChange,holidayMode,onHolidayMode}:{value:string[];onChange:(v:string[])=>void;holidayMode:string;onHolidayMode:(v:string)=>void}){
 const paint=useRef<string|null>(null);
 const [info,setInfo]=useState<any>(null);
 const [busy,setBusy]=useState(false);
 const slots=value&&value.length===7?value:DAYS.map(()=>EMPTY);
 const hours=slots.reduce((sum,day)=>sum+day.split('').filter(c=>c==='1').length,0)*0.5;

 useEffect(()=>{api('/api/holidays').then(setInfo).catch(()=>{});},[]);
 useEffect(()=>{const up=()=>{paint.current=null};window.addEventListener('pointerup',up);return()=>window.removeEventListener('pointerup',up);},[]);

 function set(day:number,slot:number,on:boolean){const next=slots.slice();const chars=next[day].split('');chars[slot]=on?'1':'0';next[day]=chars.join('');onChange(next);}
 function down(day:number,slot:number){const on=slots[day][slot]!=='1';paint.current=on?'1':'0';set(day,slot,on);}
 function move(e:any){if(!paint.current)return;const el=document.elementFromPoint(e.clientX,e.clientY)?.closest('[data-day]') as HTMLElement|null;if(!el)return;const day=Number(el.dataset.day),slot=Number(el.dataset.slot);if(slots[day]?.[slot]!==paint.current)set(day,slot,paint.current==='1');}
 function wholeDay(day:number){const has=slots[day].includes('1');const next=slots.slice();next[day]=has?EMPTY:range('18:30','20:00');onChange(next);}
 async function sync(){setBusy(true);try{const result=await api('/api/holidays/sync?year='+new Date().getFullYear(),'POST',{},20000);setInfo(result);toast.success(`已同步 ${result.count} 天法定节假日`);}catch(e:any){toast.error(e.message);}finally{setBusy(false);}}

 return <div className="mt-5">
  <div className="flex flex-wrap gap-2 mb-3">{TEMPLATES.map(([label,make])=><Button key={label} type="button" size="sm" variant="outline" onClick={()=>onChange(make())}>{label}</Button>)}</div>
  <div className="week-wrap">
   <div className="week-scale">{[0,6,12,18,24].map(h=><span key={h}>{String(h).padStart(2,'0')}:00</span>)}</div>
   <div onPointerMove={move} onPointerLeave={()=>{paint.current=null;}}>
    {DAYS.map((name,day)=><div className="week-row" key={name}>
     <button type="button" className="week-day" onClick={()=>wholeDay(day)} title={`切换${name}全部时段`}>{name}</button>
     <div className="week-cells">{Array.from({length:48},(_,slot)=><span key={slot} data-day={day} data-slot={slot} onPointerDown={()=>down(day,slot)} className={'week-cell'+(slots[day][slot]==='1'?' on':'')} title={`${String(Math.floor(slot/2)).padStart(2,'0')}:${slot%2?'30':'00'}`}/>)}</div>
    </div>)}
   </div>
  </div>
  <p className="mt-2 text-xs text-muted-foreground">点击或拖动格子设置，每格 30 分钟；点星期几可以整行切换。每周共 {hours} 小时。</p>

  <div className="mt-4 flex flex-wrap items-center gap-3 rounded-xl border p-3">
   <CalendarDays className="size-4 text-muted-foreground"/>
   <div className="min-w-0"><div className="text-sm font-medium">法定节假日</div><div className="text-xs text-muted-foreground">{info?.count?`已同步 ${info.year} 年 ${info.count} 天${info.source?' · '+info.source:''}`:'未同步，默认按周日安排'}</div></div>
   <label className="ml-auto flex items-center gap-2 text-sm"><Switch aria-label="法定节假日按周日安排" checked={holidayMode!=='normal'} onCheckedChange={v=>onHolidayMode(v?'sunday':'normal')}/>按周日安排</label>
   <Button type="button" size="sm" variant="outline" onClick={sync} disabled={busy}><RefreshCw className={busy?'animate-spin':''}/>{busy?'同步中…':'同步今年'}</Button>
  </div>
 </div>;
}
