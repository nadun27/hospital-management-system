import { Component, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { Api, Row } from './api.service';
import { Field, Module, modules } from './modules';
import { LookupComponent } from './lookup.component';

@Component({selector:'app-workspace',standalone:true,imports:[CommonModule,FormsModule,RouterLink,LookupComponent],templateUrl:'./workspace.component.html'})
export class WorkspaceComponent implements OnDestroy {
 api=inject(Api);private route=inject(ActivatedRoute);private subscription:Subscription;
 page='dashboard';m:Module|undefined;rows:Row[]=[];total=0;pageNumber=0;search='';loading=false;error='';notice='';
 dashboard:Row={cards:{}};reports:Record<string,Row[]>={};today=new Date();private generation=0;
 formOpen=false;formTitle='';formFields:Field[]=[];form:Row={};formError='';saving=false;requestPath='';requestMethod='post';confirmText='';rxItems:Row[]=[];
 chargePreview:Row[]=[];chargeLoading=false;private chargeGeneration=0;
 detailOpen=false;detailTitle='';sections:{name:string;rows:Row[]}[]=[];documentPatient:number|null=null;documents:Row[]=[];uploading=false;detailError='';
 calendar=false;month=this.localMonth();calendarRows:Row[]=[];
 constructor(){this.subscription=this.route.paramMap.subscribe(p=>{this.page=p.get('page')||'dashboard';this.m=modules.find(m=>m.key===this.page);this.pageNumber=0;this.search='';this.error='';this.notice='';this.calendar=false;this.formOpen=false;this.detailOpen=false;void this.load();});}
 ngOnDestroy(){this.subscription.unsubscribe();}
 localMonth(){const d=new Date();return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0');}
 objectEntries(o:Row):[string,any][]{return Object.entries(o);}
 label(key:string){return ({id:'ID',dob:'Date of birth',start_at:'Start time',end_at:'End time',patient_id:'Patient ID',doctor_id:'Doctor ID',encounter_id:'Consultation ID',appointment_id:'Appointment ID',total:'Total (LKR)',paid:'Paid (LKR)',balance:'Balance (LKR)',amount:'Amount (LKR)',price:'Price (LKR)',unit_price:'Unit price (LKR)',daily_rate:'Daily rate (LKR)',consultation_fee:'Fee (LKR)'} as Record<string,string>)[key]||key.replace(/_/g,' ').replace(/^./,s=>s.toUpperCase());}
 display(value:any,key=''):string{
  if(value===null||value===undefined||value==='')return '—';
  if(typeof value==='boolean'||key==='active')return value===true||value===1?'Yes':'No';
  if(['amount','total','paid','balance','price','unit_price','daily_rate','consultation_fee','received'].includes(key))return Number(value).toLocaleString('en-LK',{minimumFractionDigits:2,maximumFractionDigits:2});
  const text=String(value);return /^\d{4}-\d{2}-\d{2}T/.test(text)?text.replace('T',' '):text;
 }
 columns(rows:Row[]){return rows.length?Object.keys(rows[0]):[];}
 canWrite(){return !!this.m&&this.m.write.length>0&&this.api.can(...this.m.write);}
 async load(){
  const generation=++this.generation;this.loading=true;this.error='';
  try{
   if(this.page==='dashboard'){const data=await this.api.get('/dashboard');if(generation===this.generation)this.dashboard=data;}
   else if(this.page==='reports'){const data=await this.api.get<Record<string,Row[]>>('/reports');if(generation===this.generation)this.reports=data;}
   else if(this.m){const result=await this.api.get('/records/'+this.page+'?q='+encodeURIComponent(this.search)+'&page='+this.pageNumber);if(generation===this.generation){this.rows=result['rows'];this.total=result['total'];}}
   else this.error='This page does not exist.';
  }catch(e){if(generation===this.generation)this.error=this.api.message(e);}finally{if(generation===this.generation)this.loading=false;}
 }
 find(){this.pageNumber=0;void this.load();}
 paginate(delta:number){this.pageNumber+=delta;void this.load();}
 open(row?:Row){
  if(!this.m)return;this.formTitle=(row?'Edit ':'Add ')+this.m.title.toLowerCase();this.formFields=this.m.fields.map(f=>({...f}));
  this.form={};this.formError='';this.confirmText='';this.rxItems=[];this.chargePreview=[];
  for(const f of this.formFields){let value=row?.[f.key]??(f.type==='checkbox'?true:f.type==='number'?0:null);
   if(f.type==='datetime-local'&&value)value=String(value).replace(' ','T').slice(0,16);
   if(f.type==='checkbox')value=value===true||value===1;
   if(!row&&f.type==='select'&&f.required)value=f.options?.[0]||null;
   this.form[f.key]=value;
  }
  if(this.page==='users'){const password=this.formFields.find(f=>f.key==='password');if(password)password.required=!row;}
  this.requestPath=(this.m.path||'/records/'+this.page)+(row?'/'+row['id']:'');this.requestMethod=row?'put':'post';this.formOpen=true;
 }
 action(title:string,path:string,fields:Field[]=[],base:Row={},message=''){
  this.formTitle=title;this.requestPath=path;this.requestMethod='post';this.formFields=fields;this.form={...base};this.formError='';this.confirmText=message;this.rxItems=[];this.chargePreview=[];this.formOpen=true;
 }
 cancel(row:Row){this.action('Cancel appointment #'+row['id'],'/appointments/'+row['id']+'/cancel',[],{},'Cancel this appointment? Its slot will become available for a new booking.');}
 collect(row:Row){this.action('Collect sample #'+row['id'],'/lab/'+row['id']+'/action',[],{action:'collect'},'Confirm that the sample has been collected.');}
 result(row:Row){this.action('Enter laboratory result #'+row['id'],'/lab/'+row['id']+'/action',[{key:'result_text',label:'Test result',type:'textarea',required:true}],{action:'result'});}
 dispense(row:Row){this.action('Dispense prescription #'+row['id'],'/prescriptions/'+row['id']+'/dispense',[],{},'Dispense every item in this prescription? Stock and expiry dates will be checked before any stock is deducted.');}
 discharge(row:Row){this.action('Discharge admission #'+row['id'],'/admissions/'+row['id']+'/discharge',[],{},'Discharge this patient and create the admission charge? Each started 24-hour period is charged as one day, with a minimum of one day.');}
 payment(row:Row){this.action('Record payment · Invoice #'+row['id'],'/invoices/'+row['id']+'/payments',[{key:'amount',label:'Amount (LKR)',type:'number',required:true,min:0.01,max:Number(row['balance'])},{key:'method',label:'Method',type:'select',required:true,options:['CASH','CARD','BANK_TRANSFER']}],{amount:Number(row['balance']),method:'CASH'});}
 async fieldChanged(field:Field){
  if(this.page==='invoices'&&this.requestPath==='/invoices'&&field.key==='patient_id'){
   const gen=++this.chargeGeneration;this.chargePreview=[];this.chargeLoading=true;
   try{const rows=await this.api.get<Row[]>('/charges?patient='+this.form['patient_id']);if(gen===this.chargeGeneration)this.chargePreview=rows;}
   catch(e){if(gen===this.chargeGeneration)this.formError=this.api.message(e);}finally{if(gen===this.chargeGeneration)this.chargeLoading=false;}
  }
 }
 chargeTotal(){return this.chargePreview.reduce((sum,r)=>sum+Number(r['amount']),0);}
 addItem(){this.rxItems.push({medicine_id:null,quantity:1,instructions:''});}
 async save(){
  this.formError='';
  for(const f of this.formFields)if(f.required&&f.type!=='checkbox'&&(this.form[f.key]===null||this.form[f.key]===undefined||String(this.form[f.key]).trim()==='')){this.formError='Please complete '+f.label.toLowerCase()+'.';return;}
  const body={...this.form};
  if(this.requestPath==='/prescriptions'){if(!this.rxItems.length){this.formError='Add at least one prescription item.';return;}body['items']=this.rxItems;}
  this.saving=true;
  try{const result=await (this.requestMethod==='put'?this.api.put(this.requestPath,body):this.api.post(this.requestPath,body));this.formOpen=false;this.notice=result['id']?'Saved successfully · Record #'+result['id']:'Action completed successfully.';await this.load();}
  catch(e){this.formError=this.api.message(e);}finally{this.saving=false;}
 }
 async detail(row:Row){
  this.detailTitle=this.m?.title+' · #'+row['id'];this.sections=[];this.documents=[];this.documentPatient=null;this.detailError='';this.detailOpen=true;
  try{
   if(this.page==='invoices'){
    const result=await this.api.get('/invoices/'+row['id']);this.detailTitle='Invoice / receipt #'+row['id'];
    const balance=Number(result['invoice']['total'])-result['payments'].reduce((s:number,p:Row)=>s+Number(p['amount']),0);
    this.sections=[{name:'Invoice',rows:[{...result['invoice'],balance}]},{name:'Charges',rows:result['items'].map((r:Row)=>({description:r['description'],amount:r['amount']}))},{name:'Payments / receipts',rows:result['payments']}];
   }else if(this.page==='prescriptions')this.sections=[{name:'Prescription',rows:[row]},{name:'Medicines',rows:await this.api.get<Row[]>('/prescriptions/'+row['id']+'/items')}];
   else this.sections=[{name:'Record details',rows:[row]}];
  }catch(e){this.detailError=this.api.message(e);}
 }
 async history(row:Row){
  this.detailTitle='Medical history · '+row['name'];this.sections=[];this.documentPatient=null;this.detailError='';this.detailOpen=true;
  try{const h=await this.api.get('/patients/'+row['id']+'/history');this.sections=[{name:'Patient information',rows:[h['patient']]},{name:'Consultations',rows:h['consultations']},{name:'Laboratory',rows:h['laboratory']},{name:'Prescriptions',rows:h['prescriptions']},{name:'Nursing observations',rows:h['nursing']}];}catch(e){this.detailError=this.api.message(e);}
 }
 async showDocuments(row:Row){this.detailTitle='Documents · '+row['name'];this.sections=[];this.detailError='';this.detailOpen=true;this.documentPatient=row['id'];await this.refreshDocuments();}
 async refreshDocuments(){try{this.documents=await this.api.get<Row[]>('/patients/'+this.documentPatient+'/documents');}catch(e){this.detailError=this.api.message(e);}}
 async upload(event:Event){
  const input=event.target as HTMLInputElement,file=input.files?.[0];if(!file)return;
  this.uploading=true;this.detailError='';try{const body=new FormData();body.append('file',file);await this.api.post('/patients/'+this.documentPatient+'/documents',body);await this.refreshDocuments();}catch(e){this.detailError=this.api.message(e);}finally{this.uploading=false;input.value='';}
 }
 print(){window.print();}
 exportRows(rows:Row[],title:string){
  if(!rows.length)return;const keys=Object.keys(rows[0]);
  const escape=(v:any)=>{let s=v==null?'':String(v);if(/^[=+@\-\t\r]/.test(s))s="'"+s;return '"'+s.replace(/"/g,'""')+'"';};
  const text='\uFEFF'+[keys.map(k=>escape(this.label(k))).join(','),...rows.map(r=>keys.map(k=>escape(r[k])).join(','))].join('\r\n');
  const url=URL.createObjectURL(new Blob([text],{type:'text/csv;charset=utf-8'})),a=document.createElement('a');a.href=url;a.download=title.replace(/[^a-zA-Z0-9_-]/g,'_')+'.csv';a.click();URL.revokeObjectURL(url);
 }
 async calendarView(){this.calendar=!this.calendar;if(this.calendar)await this.loadCalendar();}
 async loadCalendar(){this.error='';try{this.calendarRows=await this.api.get<Row[]>('/calendar?month='+this.month);}catch(e){this.error=this.api.message(e);}}
 calendarDays(){const [y,m]=this.month.split('-').map(Number),first=new Date(y,m-1,1),offset=(first.getDay()+6)%7,last=new Date(y,m,0).getDate();return Array.from({length:Math.ceil((offset+last)/7)*7},(_,i)=>i-offset+1).map(d=>d>0&&d<=last?d:0);}
 onDay(day:number){const date=this.month+'-'+String(day).padStart(2,'0');return this.calendarRows.filter(r=>String(r['start_at']).startsWith(date));}
 quickLinks(){return modules.filter(m=>m.write.length&&this.api.can(...m.write)).slice(0,4);}
}
