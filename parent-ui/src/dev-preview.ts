import {defaults} from './schema';
import math from '../../data/banks/math-100.json';

let config={...defaults,version:1};
let learning:any={usedMinutes:18,remainingSeconds:720,roundsDoneToday:1,extraPool:2,reason:'',free:false,override:null};
let questions:any[]=math.items.slice(0,12).map((q,i)=>({...q,id:'preview-'+i,type:'single',subject:'数学',level:'二年级',difficulty:1,enabled:true,a:q.a.map(String)}));
let device:any={name:'客厅电视 · 开发预览',flavor:'tv',version:'0.3.10',ip:'127.0.0.1',port:5173,buglyConsent:false,monitor:{service:true,overlay:true,usage:true,accessibility:true}};
let grading:any={mode:'parent',passScore:60,provider:'',endpoint:'',model:'',hasKey:false,providers:[]};
let integration:any={enabled:false,read:true,control:false,hasToken:false};
const update={current:'0.3.10',version:'0.3.10',checking:false,hasUpdate:false,mirrors:[],log:[],api:''};

// In-memory preview data: no device requests or persistent authentication.
export async function previewApi(path:string,method:string,body:any){
 const route=path.split('?')[0];
 if(route==='/api/state')return structuredClone({config,learning,questions:questions.length,repetition:{studied:8,due:3}});
 if(route==='/api/remote'){
  learning.override=body.action==='cancel'?null:{mode:body.action,remainingSeconds:(body.minutes||30)*60,at:Date.now()};
  return structuredClone({learning});
 }
 if(route==='/api/config'){if(method==='PUT')config={...config,...body,version:config.version+1};return structuredClone({config});}
 if(route==='/api/questions'){
  if(method==='POST'){const items=body.items||[body];for(const item of items){const index=questions.findIndex(q=>q.id===item.id);if(index>=0)questions[index]={...item};else questions.push({...item,id:'preview-'+crypto.randomUUID()});}return{ok:true,added:items.length};}
  return structuredClone({items:questions,total:questions.length});
 }
 if(route==='/api/questions/delete'){const count=questions.length;questions=questions.filter(q=>!body.ids.includes(q.id));return{deleted:count-questions.length};}
 if(route.startsWith('/api/questions/')&&method==='DELETE'){questions=questions.filter(q=>q.id!==decodeURIComponent(route.split('/').pop()!));return{ok:true};}
 if(route==='/api/device'){if(method==='PUT')device={...device,...body};return structuredClone(device);}
 if(route==='/api/device/bugly'){device.buglyConsent=!!body.enabled;return{ok:true};}
 if(route==='/api/grading'){if(method==='PUT')grading={...grading,...body};return structuredClone(grading);}
 if(route==='/api/reviews')return{items:[]};
 if(route==='/api/history')return{items:Array.from({length:7},(_,i)=>{const day=new Date();day.setDate(day.getDate()-i);return{label:day.toLocaleDateString('zh-CN',{month:'numeric',day:'numeric'}),watch:[18,32,25,40,16,35,28][i],answered:15+i*2,accuracy:80+i,rounds:2};})};
 if(route==='/api/events')return{items:[{type:'quiz_pass',ts:Date.now()-600000,payload:{minutes:10}},{type:'answer',ts:Date.now()-700000,payload:{correct:true}}]};
 if(route==='/api/update'||route==='/api/update/check')return structuredClone(update);
 if(route==='/api/integration'){if(method==='PUT')integration={...integration,...body};return structuredClone(integration);}
 if(route==='/api/logs')return{lines:[]};
 if(route==='/api/holidays')return{year:new Date().getFullYear(),count:0};
 if(route==='/api/logout')return{ok:true};
 throw new Error('开发预览暂不支持此操作');
}
