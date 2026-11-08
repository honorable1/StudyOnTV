import {useEffect,useState} from 'react';
import {useMutation,useQueryClient} from '@tanstack/react-query';
import {MessageCircleHeart} from 'lucide-react';
import {toast} from 'sonner';
import {Button} from '@/components/ui/button';
import {Input} from '@/components/ui/input';
import {Textarea} from '@/components/ui/textarea';
import {api} from './api';
import {Panel,Row} from './RulesPage';
import {Field} from './QuestionBank';

const lines=(v:string)=>v.split('\n').map(s=>s.trim()).filter(Boolean);
const themes=[
 {id:'dark',name:'深色',note:'推荐 · 适合晚上和客厅',bg:'#141414',card:'#222222',text:'#E8E8E8',focus:'#2A2A2A',ring:'rgba(229,229,229,.72)'},
 {id:'light',name:'浅色',note:'适合白天明亮的房间',bg:'#F4F4F4',card:'#FFFFFF',text:'#1E1E1E',focus:'#FFFFFF',ring:'rgba(30,30,30,.5)'},
 {id:'system',name:'跟随系统',note:'跟随电视的深色模式设置',bg:'linear-gradient(135deg,#141414 50%,#F4F4F4 50%)',card:'#999999',text:'#999999',focus:'#B3B3B3',ring:'rgba(120,120,120,.7)'}];

/** 答题反馈（参考多邻国）：答完在题目下方显示结果和鼓励语，孩子按“继续”进入下一题 */
export default function FeedbackPanel({state,sessionId}:{state:any,sessionId:number}){
 const client=useQueryClient();
 const config=state?.config;
 const [praise,setPraise]=useState(''),[encourage,setEncourage]=useState(''),[auto,setAuto]=useState('0'),[dirty,setDirty]=useState(false);
 useEffect(()=>{if(config&&!dirty){setPraise((config.praisePhrases||[]).join('\n'));setEncourage((config.encouragePhrases||[]).join('\n'));setAuto(String(config.autoNextSeconds??0));}},[config]);
 const theme=useMutation({mutationFn:(id:string)=>api('/api/config','PUT',{theme:id}),onSuccess:()=>{client.invalidateQueries({queryKey:['state',sessionId]});toast.success('外观已应用，电视画面几秒内会更新');},onError:(e:any)=>toast.error(e.message)});
 const save=useMutation({mutationFn:(body:any)=>api('/api/config','PUT',body),onSuccess:()=>{setDirty(false);client.invalidateQueries({queryKey:['state',sessionId]});toast.success('答题反馈已保存');},onError:(e:any)=>toast.error(e.message)});
 function submit(reset=false){const seconds=Number(auto);if(!Number.isInteger(seconds)||seconds<0||seconds>30)return toast.error('自动下一题需要为 0–30 秒');const p=reset?[]:lines(praise),e=reset?[]:lines(encourage);if([...p,...e].some(s=>s.length>40))return toast.error('每句不超过 40 个字');save.mutate({praisePhrases:p,encouragePhrases:e,autoNextSeconds:seconds});}
 return <Panel title="界面与答题反馈" icon={MessageCircleHeart} description="学习页主题，以及答完一题后的结果提示">
  <p className="font-medium text-sm mb-3">电视学习页外观</p>
  <div className="grid grid-cols-3 gap-3 mb-5">{themes.map(t=>{const current=['dark','light','system'].includes(config?.theme)?config.theme:'dark';const on=current===t.id;return <button key={t.id} type="button" aria-pressed={on} disabled={theme.isPending} onClick={()=>theme.mutate(t.id)} className={'rounded-xl border p-2 text-left transition '+(on?'ring-2 ring-primary border-primary':'hover:border-primary/50')}><div className="rounded-lg p-3 h-20 flex flex-col justify-between" style={{background:t.bg}}><div className="h-2 w-10 rounded-full" style={{background:t.text,opacity:.85}}/><div className="flex gap-1.5"><div className="h-6 flex-1 rounded-md" style={{background:t.focus,boxShadow:`inset 0 0 0 2px ${t.ring}`}}/><div className="h-6 flex-1 rounded-md" style={{background:t.card}}/></div></div><div className="mt-2 text-sm font-medium">{t.name}{on&&' ✓'}</div><div className="text-xs text-muted-foreground">{t.note}</div></button>;})}</div>
  <Row title="进入下一题" note="0 表示由孩子手动选择“继续”（推荐）"><div className="flex items-center gap-2"><Input aria-label="自动下一题秒数" className="w-20 text-center" type="number" min={0} max={30} value={auto} onChange={e=>{setDirty(true);setAuto(e.target.value);}}/><span className="text-sm text-muted-foreground">秒后自动</span></div></Row>
  <details className="text-sm mt-3"><summary className="cursor-pointer text-muted-foreground">高级：自定义鼓励语</summary><div className="grid sm:grid-cols-2 gap-4 mt-4">
   <Field label="答对时（每行一句，随机显示）"><Textarea rows={6} value={praise} onChange={e=>{setDirty(true);setPraise(e.target.value);}}/></Field>
   <Field label="答错时（之后会显示正确答案）"><Textarea rows={6} value={encourage} onChange={e=>{setDirty(true);setEncourage(e.target.value);}}/></Field>
  </div><p className="text-xs text-muted-foreground mb-3">连续答对 3 题以上会自动加上“连续答对 N 题！”。清空后保存会恢复默认。</p></details>
  <div className="flex justify-end gap-2 mt-4"><Button variant="ghost" disabled={save.isPending} onClick={()=>submit(true)}>恢复默认</Button><Button disabled={save.isPending||!dirty} onClick={()=>submit()}>保存</Button></div>
 </Panel>
}
