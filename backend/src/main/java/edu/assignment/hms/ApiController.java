package edu.assignment.hms;

import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.sql.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final Auth auth;private final Db db;private final Catalog catalog;private final HospitalService hospital;private final Queries queries;
    public ApiController(Auth auth,Db db,Catalog catalog,HospitalService hospital,Queries queries){this.auth=auth;this.db=db;this.catalog=catalog;this.hospital=hospital;this.queries=queries;}
    @PostMapping("/auth/login") Map<String,Object> login(HttpServletRequest req,@RequestBody Map<String,Object>b){return auth.login(req,b);}
    @GetMapping("/auth/me") Map<String,Object> me(HttpServletRequest req){var u=auth.require(req);return Map.of("user",u,"csrf",req.getSession().getAttribute("csrf"));}
    @PostMapping("/auth/logout") Map<String,Object> logout(HttpServletRequest req){var u=auth.require(req);db.tx(c->{Db.audit(c,u.id(),"LOGOUT","users",u.id());return null;});req.getSession().invalidate();return Map.of("ok",true);}
    @PostMapping("/auth/password") Map<String,Object> password(HttpServletRequest req,@RequestBody Map<String,Object>b){
        var u=auth.require(req);String old=Input.password(b,"old_password",true),next=Input.password(b,"new_password",true);
        db.tx(c->{var row=Db.one(c,"SELECT password_hash FROM users WHERE id=? FOR UPDATE",u.id());
            if(!Passwords.matches(old,row.get("password_hash").toString()))throw new ApiError(400,"Current password is incorrect.");
            Db.update(c,"UPDATE users SET password_hash=?,auth_version=auth_version+1 WHERE id=?",Passwords.hash(next),u.id());Db.audit(c,u.id(),"PASSWORD_CHANGE","users",u.id());return null;});
        req.getSession().invalidate();return Map.of("ok",true);
    }
    @GetMapping("/dashboard") Map<String,Object> dashboard(HttpServletRequest req){return queries.dashboard(auth.require(req).role());}
    @GetMapping("/records/{key}") Map<String,Object> records(HttpServletRequest req,@PathVariable String key,@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="0")int page){return catalog.list(req,key,q,page);}
    @PostMapping("/records/{key}") Map<String,Object> create(HttpServletRequest req,@PathVariable String key,@RequestBody Map<String,Object>b){return Map.of("id",catalog.save(req,key,null,b));}
    @PutMapping("/records/{key}/{id}") Map<String,Object> update(HttpServletRequest req,@PathVariable String key,@PathVariable long id,@RequestBody Map<String,Object>b){return Map.of("id",catalog.save(req,key,id,b));}
    @GetMapping("/lookups/{kind}") List<Map<String,Object>> lookup(HttpServletRequest req,@PathVariable String kind,@RequestParam(defaultValue="")String q){return queries.lookup(auth.require(req).role(),kind,q);}
    @PostMapping("/appointments") Map<String,Object> book(HttpServletRequest req,@RequestBody Map<String,Object>b){return Map.of("id",hospital.appointment(auth.require(req,"RECEPTIONIST").id(),null,b));}
    @PutMapping("/appointments/{id}") Map<String,Object> reschedule(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object>b){return Map.of("id",hospital.appointment(auth.require(req,"RECEPTIONIST").id(),id,b));}
    @PostMapping("/appointments/{id}/cancel") Map<String,Object> cancel(HttpServletRequest req,@PathVariable long id){hospital.cancel(auth.require(req,"RECEPTIONIST").id(),id);return Map.of("ok",true);}
    @GetMapping("/calendar") List<Map<String,Object>> calendar(HttpServletRequest req,@RequestParam String month){
        auth.require(req,"RECEPTIONIST","DOCTOR","NURSE");YearMonth m;
        try{m=YearMonth.parse(month);}catch(Exception e){throw new ApiError(400,"Use YYYY-MM for month.");}
        return db.read(c->Db.query(c,"SELECT a.id,a.start_at,a.end_at,a.status,p.name AS patient,d.name AS doctor FROM appointments a JOIN patients p ON p.id=a.patient_id JOIN doctors d ON d.id=a.doctor_id WHERE a.start_at>=? AND a.start_at<? ORDER BY a.start_at",m.atDay(1).atStartOfDay(),m.plusMonths(1).atDay(1).atStartOfDay()));
    }
    @PostMapping("/encounters") Map<String,Object> encounter(HttpServletRequest req,@RequestBody Map<String,Object>b){return Map.of("id",hospital.encounter(auth.require(req,"DOCTOR").id(),b));}
    @PostMapping("/lab") Map<String,Object> lab(HttpServletRequest req,@RequestBody Map<String,Object>b){return Map.of("id",hospital.lab(auth.require(req,"DOCTOR").id(),b));}
    @PostMapping("/lab/{id}/action") Map<String,Object> labAction(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object>b){hospital.labAction(auth.require(req,"LAB").id(),id,b);return Map.of("ok",true);}
    @PostMapping("/prescriptions") Map<String,Object> prescribe(HttpServletRequest req,@RequestBody Map<String,Object>b){return Map.of("id",hospital.prescribe(auth.require(req,"DOCTOR").id(),b));}
    @PostMapping("/prescriptions/{id}/dispense") Map<String,Object> dispense(HttpServletRequest req,@PathVariable long id){hospital.dispense(auth.require(req,"PHARMACIST").id(),id);return Map.of("ok",true);}
    @GetMapping("/prescriptions/{id}/items") List<Map<String,Object>> items(HttpServletRequest req,@PathVariable long id){auth.require(req,"DOCTOR","PHARMACIST","NURSE");return db.read(c->Db.query(c,"SELECT i.*,m.name,m.batch_no FROM prescription_items i JOIN medicines m ON m.id=i.medicine_id WHERE i.prescription_id=?",id));}
    @PostMapping("/admissions") Map<String,Object> admit(HttpServletRequest req,@RequestBody Map<String,Object>b){return Map.of("id",hospital.admit(auth.require(req,"RECEPTIONIST").id(),b));}
    @PostMapping("/admissions/{id}/discharge") Map<String,Object> discharge(HttpServletRequest req,@PathVariable long id){hospital.discharge(auth.require(req,"RECEPTIONIST").id(),id);return Map.of("ok",true);}
    @PostMapping("/invoices") Map<String,Object> invoice(HttpServletRequest req,@RequestBody Map<String,Object>b){return Map.of("id",hospital.invoice(auth.require(req,"ACCOUNTANT").id(),b));}
    @GetMapping("/invoices/{id}") Map<String,Object> invoiceDetail(HttpServletRequest req,@PathVariable long id){auth.require(req,"ACCOUNTANT","RECEPTIONIST");return hospital.invoiceDetail(id);}
    @PostMapping("/invoices/{id}/payments") Map<String,Object> pay(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object>b){return Map.of("id",hospital.pay(auth.require(req,"ACCOUNTANT").id(),id,b));}
    @GetMapping("/charges") List<Map<String,Object>> charges(HttpServletRequest req,@RequestParam long patient){auth.require(req,"ACCOUNTANT");return db.read(c->Db.query(c,"SELECT id,description,amount FROM charges WHERE patient_id=? AND invoice_id IS NULL ORDER BY id",patient));}
    @GetMapping("/patients/{id}/history") Map<String,Object> history(HttpServletRequest req,@PathVariable long id){auth.require(req,"DOCTOR","NURSE");return queries.history(id);}
    @GetMapping("/reports") Map<String,Object> reports(HttpServletRequest req){return queries.reports(auth.require(req).role());}
    @PostMapping("/users") Map<String,Object> user(HttpServletRequest req,@RequestBody Map<String,Object>b){return saveUser(req,null,b);}
    @PutMapping("/users/{id}") Map<String,Object> editUser(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object>b){return saveUser(req,id,b);}
    private Map<String,Object> saveUser(HttpServletRequest req,Long id,Map<String,Object>b){
        var u=auth.require(req,"ADMIN");String username=Input.text(b,"username",60,true),name=Input.text(b,"name",120,true),role=Input.choice(b,"role",Auth.ROLES);
        if(!username.matches("[A-Za-z0-9_.-]{3,60}"))throw new ApiError(400,"Username must contain 3-60 letters, digits, underscores, dots or hyphens.");
        boolean active=Input.bool(b,"active");String password=Input.password(b,"password",id==null);String hash=password==null?null:Passwords.hash(password);
        if(id!=null&&id==u.id()&&(!active||!role.equals("ADMIN")))throw new ApiError(400,"You cannot remove your own administrator access.");
        long result=db.tx(c->{
            long resultId;
            if(id==null)resultId=Db.insert(c,"INSERT INTO users(username,name,password_hash,role,active) VALUES (?,?,?,?,?)",username,name,hash,role,active);
            else{Db.one(c,"SELECT id FROM users WHERE id=? FOR UPDATE",id);Db.update(c,"UPDATE users SET username=?,name=?,role=?,active=? WHERE id=?",username,name,role,active,id);
                if(hash!=null)Db.update(c,"UPDATE users SET password_hash=?,auth_version=auth_version+1 WHERE id=?",hash,id);resultId=id;}
            Db.audit(c,u.id(),id==null?"CREATE_USER":"UPDATE_USER","users",resultId);return resultId;
        });return Map.of("id",result);
    }
    @GetMapping("/patients/{id}/documents") List<Map<String,Object>> documents(HttpServletRequest req,@PathVariable long id){auth.require(req,"RECEPTIONIST","DOCTOR","NURSE");return db.read(c->Db.query(c,"SELECT id,filename,content_type,uploaded_at FROM patient_documents WHERE patient_id=? ORDER BY id DESC",id));}
    @PostMapping("/patients/{id}/documents") Map<String,Object> upload(HttpServletRequest req,@PathVariable long id,@RequestParam("file")MultipartFile file)throws Exception{
        var u=auth.require(req,"RECEPTIONIST","DOCTOR","NURSE");byte[] bytes=file.getBytes();
        if(bytes.length==0||bytes.length>5*1024*1024)throw new ApiError(400,"Upload a nonempty file up to 5 MB.");
        String type;
        if(bytes.length>=5&&bytes[0]=='%'&&bytes[1]=='P'&&bytes[2]=='D'&&bytes[3]=='F'&&bytes[4]=='-')type="application/pdf";
        else if(bytes.length>=8&&(bytes[0]&255)==137&&bytes[1]=='P'&&bytes[2]=='N'&&bytes[3]=='G')type="image/png";
        else if(bytes.length>=3&&(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255)type="image/jpeg";
        else throw new ApiError(400,"Only PDF, PNG and JPEG documents are supported.");
        String filename=Optional.ofNullable(file.getOriginalFilename()).orElse("document").replaceAll("[^A-Za-z0-9._ -]","_");
        if(filename.length()>180)filename=filename.substring(filename.length()-180);final String name=filename;
        return Map.of("id",db.tx(c->{long doc=Db.insert(c,"INSERT INTO patient_documents(patient_id,filename,content_type,content) VALUES (?,?,?,?)",id,name,type,bytes);Db.audit(c,u.id(),"UPLOAD","patient_documents",doc);return doc;}));
    }
    @GetMapping("/documents/{id}") ResponseEntity<byte[]> download(HttpServletRequest req,@PathVariable long id){
        var u=auth.require(req,"RECEPTIONIST","DOCTOR","NURSE");return db.tx(c->{
            try(PreparedStatement p=c.prepareStatement("SELECT filename,content_type,content FROM patient_documents WHERE id=?")){
                p.setLong(1,id);try(ResultSet r=p.executeQuery()){
                    if(!r.next())throw new ApiError(404,"Document not found");
                    byte[] bytes=r.getBytes("content");String filename=r.getString("filename"),type=r.getString("content_type");Db.audit(c,u.id(),"DOWNLOAD","patient_documents",id);
                    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(filename).build().toString()).header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(type)).body(bytes);
                }
            }
        });
    }
}
