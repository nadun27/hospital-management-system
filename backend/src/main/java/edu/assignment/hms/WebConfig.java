package edu.assignment.hms;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.*;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry){
        registry.addInterceptor(new HandlerInterceptor(){
            @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler){
                res.setHeader("Cache-Control","no-store");res.setHeader("X-Content-Type-Options","nosniff");
                res.setHeader("X-Frame-Options","DENY");res.setHeader("Referrer-Policy","same-origin");return true;
            }
        }).addPathPatterns("/api/**");
    }
    @Override public void addViewControllers(ViewControllerRegistry registry){
        for(String page:new String[]{"dashboard","patients","appointments","encounters","nursing","admissions","lab","prescriptions","medicines","tests","invoices","payments","doctors","schedules","beds","departments","employees","attendance","leave","users","audit","reports"})
            registry.addViewController("/"+page).setViewName("forward:/index.html");
    }
}
