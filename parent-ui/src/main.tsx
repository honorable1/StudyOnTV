import {useEffect,useState,lazy,Suspense,type ReactNode} from 'react';
import {createRoot} from 'react-dom/client';
import {QueryClient,QueryClientProvider,useQuery} from '@tanstack/react-query';
import {BookOpen,ClipboardCheck,SlidersHorizontal,ChartColumn,Leaf,LogOut,ArrowRight,LockKeyhole,Monitor,Gamepad2,Wifi,Settings,Sun,Moon,Laptop,ChevronsUpDown,Tv,Palette,KeyRound,CloudDownload,RadioTower,FileText,Bot,Eye,EyeOff} from 'lucide-react';
import {Toaster,toast} from 'sonner';
import {Button} from '@/components/ui/button';
import {Input} from '@/components/ui/input';
import {Dialog,DialogContent,DialogHeader,DialogTitle,DialogDescription} from '@/components/ui/dialog';
import {Label} from '@/components/ui/label';
import {Card,CardHeader,CardTitle,CardDescription,CardContent} from '@/components/ui/card';
import {Badge} from '@/components/ui/badge';
import {Separator} from '@/components/ui/separator';
import {Sidebar,SidebarContent,SidebarFooter,SidebarGroup,SidebarGroupLabel,SidebarHeader,SidebarInset,SidebarMenu,SidebarMenuBadge,SidebarMenuButton,SidebarMenuItem,SidebarProvider,SidebarRail,SidebarTrigger} from '@/components/ui/sidebar';
import {DropdownMenu,DropdownMenuContent,DropdownMenuItem,DropdownMenuLabel,DropdownMenuSeparator,DropdownMenuTrigger,DropdownMenuRadioGroup,DropdownMenuRadioItem} from '@/components/ui/dropdown-menu';
import {api,getHost,setHost,setToken,enablePreview} from './api';
import RulesPage from './RulesPage';
import RemotePage from './RemotePage';
import QuestionBank from './QuestionBank';
import GradingPage from './GradingPage';
import DevicePanel from './DevicePanel';
import LogsPage from './LogsPage';
import AiSettings from './AiSettings';
import FeedbackPanel from './FeedbackPanel';
const RecordsPage=lazy(()=>import('./RecordsPage'));
import './styles.css';

const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:3000,refetchOnWindowFocus:false},mutations:{retry:false}}});

type PageId='remote'|'rules'|'bank'|'grading'|'records'|'settings';
const pages:{id:PageId,title:string,short:string,icon:any,description:string}[]=[
 {id:'remote',title:'首页',short:'首页',icon:Gamepad2,description:'状态、解锁与今日概览'},
 {id:'grading',title:'作答批改',short:'批改',icon:ClipboardCheck,description:'处理待批作业'},
 {id:'bank',title:'题库',short:'题库',icon:BookOpen,description:'题目、分类与导入导出'},
 {id:'rules',title:'学习计划',short:'计划',icon:SlidersHorizontal,description:'任务、时段、范围与奖励'},
 {id:'records',title:'成长记录',short:'记录',icon:ChartColumn,description:'使用时间与学习进度'},
 {id:'settings',title:'设置',short:'设置',icon:Settings,description:'设备、外观、更新与安全'}];
type SectionId='device'|'display'|'update'|'security'|'integration'|'logs'|'ai';
const sections:{id:SectionId,title:string,icon:any}[]=[{id:'device',title:'设备',icon:Tv},{id:'display',title:'界面与反馈',icon:Palette},{id:'update',title:'版本更新',icon:CloudDownload},{id:'security',title:'管理密码',icon:KeyRound},{id:'integration',title:'家庭自动化',icon:RadioTower},{id:'logs',title:'诊断日志',icon:FileText},{id:'ai',title:'模型 API（高级）',icon:Bot}];

/* ---------- 记住登录：保存在本机浏览器，30 天有效；修改管理密码或断开连接后失效 ---------- */
const SESSION_KEY='sotv-panel-session';
function saveSession(host:string,token:string){try{localStorage.setItem(SESSION_KEY,JSON.stringify({host,token}));}catch{}}
function forgetSession(){try{localStorage.removeItem(SESSION_KEY);}catch{}}
function restoreSession():boolean{try{const v=JSON.parse(localStorage.getItem(SESSION_KEY)||'null');if(!v?.token||!v?.host)return false;// 面板由设备自己提供时，以当前地址为准（设备 IP 可能变了）
 setHost(location.protocol.startsWith('http')&&location.host?location.host:v.host);setToken(v.token);return true;}catch{return false;}}

/* ---------- 主题：浅色 / 深色 / 跟随系统（参考 shadcn-admin ThemeProvider） ---------- */
type Theme='light'|'dark'|'system';
function readTheme():Theme{try{const v=localStorage.getItem('sotv-panel-theme');return v==='light'||v==='dark'?v:'system';}catch{return 'system';}}
function useTheme(){const[theme,set]=useState<Theme>(readTheme);useEffect(()=>{const media=window.matchMedia('(prefers-color-scheme: dark)');const apply=()=>{const dark=theme==='dark'||(theme==='system'&&media.matches);document.documentElement.classList.toggle('dark',dark);document.querySelector('meta[name=theme-color]')?.setAttribute('content',dark?'#020617':'#ffffff');};apply();media.addEventListener('change',apply);return()=>media.removeEventListener('change',apply);},[theme]);return{theme,setTheme:(t:Theme)=>{try{localStorage.setItem('sotv-panel-theme',t);}catch{}set(t);}};}
function ThemeSwitch({theme,setTheme}:{theme:Theme,setTheme:(t:Theme)=>void}){return <DropdownMenu modal={false}><DropdownMenuTrigger asChild><Button variant="ghost" size="icon" className="rounded-full" aria-label="切换浅色或深色"><Sun className="size-[1.2rem] scale-100 rotate-0 transition-all dark:scale-0 dark:-rotate-90"/><Moon className="absolute size-[1.2rem] scale-0 rotate-90 transition-all dark:scale-100 dark:rotate-0"/></Button></DropdownMenuTrigger><DropdownMenuContent align="end"><DropdownMenuRadioGroup value={theme} onValueChange={v=>setTheme(v as Theme)}><DropdownMenuRadioItem value="light"><Sun/>浅色</DropdownMenuRadioItem><DropdownMenuRadioItem value="dark"><Moon/>深色</DropdownMenuRadioItem><DropdownMenuRadioItem value="system"><Laptop/>跟随系统</DropdownMenuRadioItem></DropdownMenuRadioGroup></DropdownMenuContent></DropdownMenu>}

/* ---------- 路由：#/rules、#/settings/update …（手机返回键可用） ---------- */
function parseHash():{page:PageId,section:SectionId}{const [p,s]=location.hash.replace(/^#\/?/,'').split('/');return{page:(pages.some(x=>x.id===p)?p:'remote') as PageId,section:(sections.some(x=>x.id===s)?s:'device') as SectionId};}
function useRoute(){const[route,set]=useState(parseHash);useEffect(()=>{const on=()=>{set(parseHash());window.scrollTo({top:0});};window.addEventListener('hashchange',on);return()=>window.removeEventListener('hashchange',on);},[]);return{...route,go:(page:PageId,section?:SectionId)=>{location.hash='/'+page+(section?'/'+section:'');}};}

function App(){const[session,setSession]=useState(()=>restoreSession()?1:0),[next,setNext]=useState(2);const theme=useTheme();function logout(){api('/api/logout','POST',{}).catch(()=>{});setToken('');forgetSession();setSession(0);client.clear();}useEffect(()=>{const handler=()=>{logout();toast.error('登录已失效，请重新验证管理密码');};window.addEventListener('session-expired',handler);return()=>window.removeEventListener('session-expired',handler);},[]);return <><Toaster richColors position="top-center" theme={theme.theme}/>{session?<Dashboard sessionId={session} logout={logout} theme={theme}/>:<Login onLogin={()=>{setSession(next);setNext(n=>n+1);}} theme={theme}/>}</>}

function Login({onLogin,theme}:{onLogin:()=>void,theme:ReturnType<typeof useTheme>}){
 const[host,updateHost]=useState(getHost()),[secret,setSecret]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
 const[visible,setVisible]=useState(false),[accepted,setAccepted]=useState(true),[crashReports,setCrashReports]=useState(true),[document,setDocument]=useState<'terms'|'privacy'|null>(null);
 async function submit(e:any){e.preventDefault();if(busy)return;setError('');if(!accepted)return setError('请先同意用户协议和隐私政策');if(secret.length<6)return setError('请输入管理密码');setBusy(true);try{setHost(host);const result=await api('/api/auth','POST',{passphrase:secret,remember:true});setToken(result.token);saveSession(getHost(),result.token);setSecret('');onLogin();}catch(e:any){setError(e.message==='Failed to fetch'?'无法连接设备，请检查设备地址和 WiFi':e.message);}finally{setBusy(false);}}
 return <main className="login-page">
  <header className="login-topbar"><div className="login-brand"><span className="brand-icon"><Leaf/></span><span>题了个题</span></div><ThemeSwitch {...theme}/></header>
  <div className="login-center"><section className="login-card" aria-labelledby="login-title">
   <div className="login-heading"><h1 id="login-title">登录家长管理</h1></div>
   <form onSubmit={submit} className="login-form">
    <div className="space-y-2"><Label htmlFor="device-host">设备地址</Label><div className="relative"><Monitor className="login-input-icon"/><Input className="login-input" id="device-host" autoComplete="off" autoCapitalize="none" spellCheck={false} inputMode="url" required value={host} onChange={e=>updateHost(e.target.value)} placeholder="192.168.1.20:8099"/></div></div>
    <div className="space-y-2"><Label htmlFor="management-secret">管理密码</Label><div className="relative"><LockKeyhole className="login-input-icon"/><Input className="login-input login-password" id="management-secret" type={visible?'text':'password'} autoComplete="current-password" required value={secret} onChange={e=>setSecret(e.target.value)} placeholder="输入管理密码"/><button className="login-password-toggle" type="button" onClick={()=>setVisible(v=>!v)} aria-label={visible?'隐藏密码':'显示密码'}>{visible?<EyeOff/>:<Eye/>}</button></div></div>
    <div className="login-consents"><div className="login-check"><input id="login-accepted" type="checkbox" checked={accepted} onChange={e=>setAccepted(e.target.checked)}/><span><label htmlFor="login-accepted">我已阅读并同意</label><button type="button" onClick={()=>setDocument('terms')}>《用户协议》</button>和<button type="button" onClick={()=>setDocument('privacy')}>《隐私政策》</button></span></div><label className="login-check"><input type="checkbox" checked={crashReports} onChange={e=>setCrashReports(e.target.checked)}/><span>同意上传异常与崩溃日志，帮助提升用户体验</span></label></div>
    {error&&<p role="alert" className="text-sm text-destructive">{error}</p>}
    <Button className="login-submit" type="submit" disabled={busy||!accepted}>{busy?'登录中…':'登录'}<ArrowRight className="size-4"/></Button>
   </form>
   {import.meta.env.DEV&&<Button type="button" variant="ghost" className="w-full mt-3 text-xs text-muted-foreground" onClick={()=>{enablePreview();client.clear();onLogin();}}>开发预览</Button>}
  </section></div>
  <Dialog open={document!==null} onOpenChange={open=>{if(!open)setDocument(null);}}><DialogContent><DialogHeader><DialogTitle>{document==='terms'?'用户协议':'隐私政策'}</DialogTitle><DialogDescription>界面预览 · 正式条款待接入</DialogDescription></DialogHeader></DialogContent></Dialog>
 </main>
}

function AppSidebar({page,go,pending,device,logout}:{page:PageId,go:(p:PageId)=>void,pending:number,device:any,logout:()=>void}){
 const groups:[string,PageId[]][]=[['学习',['remote','rules','bank','grading','records']],['系统',['settings']]];
 return <Sidebar collapsible="icon" variant="sidebar">
  <SidebarHeader><SidebarMenu><SidebarMenuItem><SidebarMenuButton size="lg" onClick={()=>go('remote')} tooltip="题了个题"><span className="brand-icon size-8 rounded-lg"><Leaf className="size-4"/></span><div className="grid flex-1 text-start text-sm leading-tight"><span className="truncate font-semibold">题了个题</span><span className="truncate text-xs text-muted-foreground">家长管理</span></div></SidebarMenuButton></SidebarMenuItem></SidebarMenu></SidebarHeader>
  <SidebarContent>{groups.map(([label,ids])=><SidebarGroup key={label}><SidebarGroupLabel>{label}</SidebarGroupLabel><SidebarMenu>{ids.map(id=>{const p=pages.find(x=>x.id===id)!;return <SidebarMenuItem key={id}><SidebarMenuButton isActive={page===id} tooltip={p.title} onClick={()=>go(id)}><p.icon/><span>{p.title}</span></SidebarMenuButton>{id==='grading'&&pending>0&&<SidebarMenuBadge>{pending}</SidebarMenuBadge>}</SidebarMenuItem>;})}</SidebarMenu></SidebarGroup>)}</SidebarContent>
  <SidebarFooter><SidebarMenu><SidebarMenuItem><DeviceMenu device={device} logout={logout} side="right"><SidebarMenuButton size="lg" className="data-[state=open]:bg-sidebar-accent"><span className="flex size-8 items-center justify-center rounded-lg bg-secondary"><Tv className="size-4"/></span><div className="grid flex-1 text-start text-sm leading-tight"><span className="truncate font-semibold">{device?.name||'被控设备'}</span><span className="truncate text-xs text-muted-foreground"><span className="status-dot"/>{getHost()}</span></div><ChevronsUpDown className="ms-auto size-4"/></SidebarMenuButton></DeviceMenu></SidebarMenuItem></SidebarMenu></SidebarFooter>
  <SidebarRail/>
 </Sidebar>}

function DeviceMenu({device,logout,children,side='bottom'}:{device:any,logout:()=>void,children:ReactNode,side?:'right'|'bottom'}){return <DropdownMenu modal={false}><DropdownMenuTrigger asChild>{children}</DropdownMenuTrigger><DropdownMenuContent className="min-w-56 rounded-lg" side={side} align="end" sideOffset={4}><DropdownMenuLabel className="font-normal"><div className="grid text-sm leading-tight"><span className="font-semibold">{device?.name||'被控设备'}</span><span className="text-xs text-muted-foreground">{device?`${device.flavor==='tv'?'电视版':'手机 / 平板版'} v${device.version}`:getHost()}</span></div></DropdownMenuLabel><DropdownMenuSeparator/><DropdownMenuItem onClick={()=>{location.hash='/settings/device';}}><Settings/>设备设置</DropdownMenuItem><DropdownMenuItem onClick={logout} variant="destructive"><LogOut/>断开并重新连接</DropdownMenuItem></DropdownMenuContent></DropdownMenu>}

function Header({title,children}:{title:string,children:ReactNode}){const[offset,setOffset]=useState(0);useEffect(()=>{const on=()=>setOffset(document.documentElement.scrollTop);document.addEventListener('scroll',on,{passive:true});return()=>document.removeEventListener('scroll',on);},[]);
 return <header className={'sticky top-0 z-30 h-14 md:h-16 '+(offset>10?'shadow-sm':'')}><div className={'relative flex h-full items-center gap-3 px-4 '+(offset>10?'after:absolute after:inset-0 after:-z-10 after:bg-background/80 after:backdrop-blur-lg':'bg-background md:bg-transparent')}><SidebarTrigger variant="outline" className="hidden md:inline-flex"/><Separator orientation="vertical" className="hidden md:block h-6"/><span className="md:hidden flex"><span className="brand-icon size-8 rounded-lg"><Leaf className="size-4"/></span></span><h2 className="text-base font-semibold truncate">{title}</h2><div className="ms-auto flex items-center gap-1">{children}</div></div></header>}

function BottomNav({page,go,pending}:{page:PageId,go:(p:PageId)=>void,pending:number}){return <nav className="bottom-nav" aria-label="主导航">{pages.map(p=><button key={p.id} type="button" aria-current={page===p.id?'page':undefined} onClick={()=>go(p.id)}><p.icon/><span>{p.short}</span>{p.id==='grading'&&pending>0&&<span className="badge">{pending>99?'99+':pending}</span>}</button>)}</nav>}

function SettingsPage({section,go,sessionId,state,logout}:{section:SectionId,go:(p:PageId,s:SectionId)=>void,sessionId:number,state:any,logout:()=>void}){
 return <div className="flex flex-col gap-4 lg:flex-row lg:gap-10">
  <aside className="lg:w-52 shrink-0 lg:sticky lg:top-20 self-start w-full"><nav className="flex gap-1 overflow-x-auto no-scrollbar lg:flex-col -mx-1 px-1">{sections.map(s=><Button key={s.id} variant={section===s.id?'secondary':'ghost'} className="justify-start shrink-0" onClick={()=>go('settings',s.id)}><s.icon/>{s.title}</Button>)}</nav></aside>
  <div className="min-w-0 flex-1 max-w-3xl">{section==='display'?<FeedbackPanel state={state} sessionId={sessionId}/>:section==='logs'?<LogsPage sessionId={sessionId}/>:section==='ai'?<AiSettings sessionId={sessionId}/>:<DevicePanel sessionId={sessionId} only={section} onPasswordChanged={logout}/>}</div>
 </div>}

function Dashboard({sessionId,logout,theme}:{sessionId:number,logout:()=>void,theme:ReturnType<typeof useTheme>}){
 const{page,section,go}=useRoute();
 const state=useQuery({queryKey:['state',sessionId],queryFn:()=>api('/api/state'),refetchInterval:5000});
 const device=useQuery({queryKey:['device',sessionId],queryFn:()=>api('/api/device')});
 const reviews=useQuery({queryKey:['reviews',sessionId],queryFn:()=>api('/api/reviews'),refetchInterval:15000});
 const pending=reviews.data?.items?.length||0;
 // 打开家长后台时自动检查一次版本；有新版本才提示
 useEffect(()=>{let stop=false;let tries=0;api('/api/update/check','POST',{}).catch(()=>{});const timer=setInterval(async()=>{tries++;try{const u=await api('/api/update');if(stop)return;if(!u.checking){clearInterval(timer);if(u.hasUpdate&&u.latest)toast.message(`发现新版本 v${u.latest}，请升级`);}}catch{}if(tries>=20)clearInterval(timer);},2500);return()=>{stop=true;clearInterval(timer);};},[]);
 const current=pages.find(p=>p.id===page)!;
 return <SidebarProvider className={page==='remote'?'home-shell':undefined}>
  <AppSidebar page={page} go={go} pending={pending} device={device.data} logout={logout}/>
  <SidebarInset className="min-w-0">
   <Header title={page==='remote'?(device.data?.name||'我的设备'):current.title}><Badge variant="outline" className="hidden sm:flex mr-1"><span className="status-dot"/>已连接</Badge><ThemeSwitch {...theme}/><div className="md:hidden"><DeviceMenu device={device.data} logout={logout}><Button variant="ghost" size="icon" className="rounded-full" aria-label="设备与连接"><Tv/></Button></DeviceMenu></div></Header>
   <main className="page-content px-4 pt-2 md:px-6 md:pt-4 w-full max-w-6xl mx-auto">
    <div className={page==='remote'?'hidden':'mb-5 hidden md:flex items-baseline justify-between gap-4'}><h1 className="text-2xl font-bold tracking-tight">{current.title}</h1><p className="shrink-0 text-sm text-muted-foreground">{current.description}</p></div>
    {state.error&&<p className="connection-error">暂未能刷新数据，请确认设备仍在同一局域网。</p>}
    {page==='remote'&&<RemotePage state={state.data} sessionId={sessionId}/>}
    {page==='rules'&&<RulesPage state={state.data} sessionId={sessionId}/>}
    {page==='bank'&&<QuestionBank sessionId={sessionId}/>}
    {page==='grading'&&<GradingPage sessionId={sessionId}/>}
    {page==='records'&&<Suspense fallback={<p className="empty-state">正在读取记录…</p>}><RecordsPage sessionId={sessionId}/></Suspense>}
    {page==='settings'&&<SettingsPage section={section} go={go} sessionId={sessionId} state={state.data} logout={logout}/>}
   </main>
  </SidebarInset>
  <BottomNav page={page} go={p=>go(p)} pending={pending}/>
 </SidebarProvider>}

createRoot(document.getElementById('root')!).render(<QueryClientProvider client={client}><App/></QueryClientProvider>);
