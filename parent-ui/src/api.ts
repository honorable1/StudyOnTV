import {sha256Hex,hmacHex} from './lib/auth-crypto';

let previewMode=false;
export function enablePreview(){previewMode=true;}
let host=location.host;
let token='';
export function setHost(value:string){const clean=value.trim().replace(/^https?:\/\//,'').replace(/\/.*$/,'');if(!clean||!/^[\w.:[\]-]+$/.test(clean))throw new Error('请填写有效的设备地址');host=clean;}
export function getHost(){return host;}
export function setToken(value:string){token=value;}
export async function api(path:string,method='GET',body?:unknown,timeoutMs=10000){if(import.meta.env.DEV&&previewMode){const {previewApi}=await import('./dev-preview');return previewApi(path,method,body);}const controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),timeoutMs);try{const response=await fetch(`http://${host}${path}`,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:body===undefined?undefined:JSON.stringify(body),cache:'no-store',signal:controller.signal});let result:any;try{result=await response.json();}catch{throw new Error('设备返回异常，请重试');}if(!response.ok||result.error){const error:any=new Error(result.message||result.error||'请求失败');error.status=response.status;if(response.status===401&&path!=='/api/auth')window.dispatchEvent(new Event('session-expired'));throw error;}return result;}catch(error){if(error.name==='AbortError')throw new Error('连接超时，请确认处于同一 WiFi');throw error;}finally{clearTimeout(timeout);}}
/**
 * 登录：优先走挑战-响应，密码本身不上网。
 *
 *   1. GET  /api/auth/challenge  → { challenge, salt }
 *   2. key   = sha256(密码 + ":" + salt)
 *   3. POST /api/auth            → { challenge, proof: HMAC-SHA256(key, challenge) }
 *
 * 抓包只能看到一次性的 challenge 与 proof，校验完即作废，无法重放。
 *
 * 失败要能登进去：设备不支持该接口（旧固件）、密码还没升级成 auth_key、或正在锁定
 * 时，`/api/auth/challenge` 会返回非 2xx 或空体，此时回落到明文 passphrase —— 服务端
 * 仍兼容这条路径，登录成功后会补写 auth_key，下次就能走挑战-响应。
 */
export async function login(passphrase:string,remember=true):Promise<{token:string}>{
 if(import.meta.env.DEV&&previewMode)return api('/api/auth','POST',{passphrase,remember});
 let challenge='',salt='';
 try{
  const controller=new AbortController();
  const timer=setTimeout(()=>controller.abort(),8000);
  try{
   const response=await fetch(`http://${host}/api/auth/challenge`,{method:'GET',cache:'no-store',signal:controller.signal});
   if(response.ok){const data:any=await response.json();if(data&&typeof data.challenge==='string'&&typeof data.salt==='string'){challenge=data.challenge;salt=data.salt;}}
  }finally{clearTimeout(timer);}
 }catch{/* 取不到挑战就退回明文协议，不能让家长卡在登录页 */}
 if(!challenge||!salt)return api('/api/auth','POST',{passphrase,remember});
 return api('/api/auth','POST',{challenge,proof:hmacHex(sha256Hex(`${passphrase}:${salt}`),challenge),remember});
}

export async function downloadLogs(){const response=await fetch(`http://${host}/api/logs/export`,{headers:{...(token?{Authorization:`Bearer ${token}`}:{})},cache:'no-store'});if(!response.ok)throw new Error('导出失败（HTTP '+response.status+'）');const blob=await response.blob();const name=(response.headers.get('content-disposition')||'').match(/filename="?([^";]+)"?/)?.[1]||'studyontv-logs.zip';const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=name;document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),3000);}
