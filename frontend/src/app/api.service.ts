import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { firstValueFrom, catchError, throwError } from 'rxjs';

export interface User { id:number; username:string; name:string; role:string; }
export type Row = Record<string,any>;
@Injectable({providedIn:'root'})
export class Api {
 private http=inject(HttpClient);
 user=signal<User|null>(null); csrf=''; ready=signal(false);
 async init(){try{this.accept(await this.get('/auth/me'));}catch{}finally{this.ready.set(true);}}
 accept(result:Row){this.user.set(result['user']);this.csrf=result['csrf'];}
 async login(username:string,password:string){this.accept(await this.post('/auth/login',{username,password}));}
 async logout(){try{await this.post('/auth/logout',{});}finally{this.user.set(null);this.csrf='';}}
 can(...roles:string[]){return this.user()?.role==='ADMIN'||roles.includes(this.user()?.role||'');}
 get<T=Row>(path:string){return firstValueFrom(this.http.get<T>('/api'+path));}
 post<T=Row>(path:string,body:any){return firstValueFrom(this.http.post<T>('/api'+path,body));}
 put<T=Row>(path:string,body:any){return firstValueFrom(this.http.put<T>('/api'+path,body));}
 message(e:unknown){return e instanceof HttpErrorResponse?(e.error?.message||'Cannot reach the server. Check that the Java backend is running.'):'The request could not be completed.';}
}
export const securityInterceptor:HttpInterceptorFn=(req,next)=>{
 const api=inject(Api);
 if(req.url.startsWith('/api/')&&!['GET','HEAD'].includes(req.method)&&api.csrf)req=req.clone({setHeaders:{'X-CSRF-Token':api.csrf}});
 return next(req).pipe(catchError((e:HttpErrorResponse)=>{
  if(e.status===401&&!req.url.endsWith('/auth/login')){api.user.set(null);api.csrf='';}
  return throwError(()=>e);
 }));
};
