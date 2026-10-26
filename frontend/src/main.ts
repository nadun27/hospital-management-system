import { bootstrapApplication } from '@angular/platform-browser';
import { provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app/app.component';
import { WorkspaceComponent } from './app/workspace.component';
import { securityInterceptor } from './app/api.service';

bootstrapApplication(AppComponent, {providers:[
 provideZoneChangeDetection({eventCoalescing:true}),
 provideHttpClient(withInterceptors([securityInterceptor])),
 provideRouter([{path:'',redirectTo:'dashboard',pathMatch:'full'},{path:':page',component:WorkspaceComponent},{path:'**',redirectTo:'dashboard'}])
]}).catch(console.error);
