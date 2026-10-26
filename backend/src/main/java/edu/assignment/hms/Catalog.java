package edu.assignment.hms;

import org.springframework.stereotype.Service;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.time.*;
import java.util.*;

/** Allowlisted CRUD for independent records; connected workflows live in HospitalService. */
@Service
public class Catalog {
    public record Entity(String table, String fields, String readRoles, String writeRoles, String sql, String search) {}
    private final Db db; private final Auth auth;
    public Catalog(Db db, Auth auth) { this.db=db; this.auth=auth; }
    public static final Map<String,Entity> ENTITIES = Map.ofEntries(
        Map.entry("departments",new Entity("departments","name","ALL","ADMIN","SELECT * FROM departments","name")),
        Map.entry("patients",new Entity("patients","name,dob,gender,phone,email,address,blood_group,allergies","RECEPTIONIST,DOCTOR,NURSE","RECEPTIONIST","SELECT * FROM patients","name,phone")),
        Map.entry("doctors",new Entity("doctors","name,department_id,specialization,consultation_fee,active","ALL","ADMIN","SELECT d.*,p.name AS department FROM doctors d JOIN departments p ON p.id=d.department_id","name,specialization,department")),
        Map.entry("schedules",new Entity("doctor_schedules","doctor_id,weekday,start_time,end_time","RECEPTIONIST,DOCTOR","ADMIN","SELECT s.*,d.name AS doctor FROM doctor_schedules s JOIN doctors d ON d.id=s.doctor_id","doctor")),
        Map.entry("tests",new Entity("test_catalog","name,price,reference_range","DOCTOR,LAB","LAB","SELECT * FROM test_catalog","name")),
        Map.entry("medicines",new Entity("medicines","name,batch_no,expiry_date,stock,unit_price,reorder_level","DOCTOR,PHARMACIST","PHARMACIST","SELECT * FROM medicines","name,batch_no")),
        Map.entry("beds",new Entity("beds","ward,bed_number,daily_rate","RECEPTIONIST,NURSE","ADMIN","SELECT b.*,CASE WHEN EXISTS(SELECT 1 FROM admissions a WHERE a.bed_id=b.id AND a.discharged_at IS NULL) THEN 'Occupied' ELSE 'Available' END AS availability FROM beds b","ward,bed_number")),
        Map.entry("employees",new Entity("employees","name,department_id,job_title,phone,email","ADMIN","ADMIN","SELECT e.*,d.name AS department FROM employees e JOIN departments d ON d.id=e.department_id","name,job_title,department")),
        Map.entry("attendance",new Entity("attendance","employee_id,work_date,status","ADMIN","ADMIN","SELECT a.*,e.name AS employee FROM attendance a JOIN employees e ON e.id=a.employee_id","employee,status")),
        Map.entry("leave",new Entity("leave_requests","employee_id,start_date,end_date,reason,status","ADMIN","ADMIN","SELECT l.*,e.name AS employee FROM leave_requests l JOIN employees e ON e.id=l.employee_id","employee,status")),
        Map.entry("appointments",new Entity("appointments","","RECEPTIONIST,DOCTOR,NURSE","","SELECT a.*,p.name AS patient,d.name AS doctor FROM appointments a JOIN patients p ON p.id=a.patient_id JOIN doctors d ON d.id=a.doctor_id","patient,doctor,status")),
        Map.entry("encounters",new Entity("encounters","","DOCTOR,NURSE","","SELECT e.*,p.name AS patient,d.name AS doctor FROM encounters e JOIN patients p ON p.id=e.patient_id JOIN doctors d ON d.id=e.doctor_id","patient,doctor,diagnosis")),
        Map.entry("lab",new Entity("lab_orders","","LAB,DOCTOR,NURSE","","SELECT l.*,p.name AS patient,t.name AS test,t.reference_range FROM lab_orders l JOIN patients p ON p.id=l.patient_id JOIN test_catalog t ON t.id=l.test_id","patient,test,status")),
        Map.entry("prescriptions",new Entity("prescriptions","","DOCTOR,PHARMACIST,NURSE","","SELECT r.*,p.name AS patient FROM prescriptions r JOIN patients p ON p.id=r.patient_id","patient,status")),
        Map.entry("admissions",new Entity("admissions","","RECEPTIONIST,NURSE,DOCTOR","","SELECT a.*,p.name AS patient,b.bed_number,b.ward FROM admissions a JOIN patients p ON p.id=a.patient_id JOIN beds b ON b.id=a.bed_id","patient,bed_number,ward")),
        Map.entry("invoices",new Entity("invoices","","ACCOUNTANT,RECEPTIONIST","","SELECT i.*,p.name AS patient,COALESCE((SELECT SUM(pay.amount) FROM payments pay WHERE pay.invoice_id=i.id),0) AS paid,i.total-COALESCE((SELECT SUM(pay.amount) FROM payments pay WHERE pay.invoice_id=i.id),0) AS balance FROM invoices i JOIN patients p ON p.id=i.patient_id","patient")),
        Map.entry("payments",new Entity("payments","","ACCOUNTANT","","SELECT pay.*,p.name AS patient FROM payments pay JOIN invoices i ON i.id=pay.invoice_id JOIN patients p ON p.id=i.patient_id","patient,method")),
        Map.entry("users",new Entity("users","","ADMIN","","SELECT id,username,name,role,active FROM users","username,name,role")),
        Map.entry("audit",new Entity("audit_logs","","ADMIN","","SELECT a.*,u.username FROM audit_logs a LEFT JOIN users u ON u.id=a.user_id","username,action,entity_type")),
        Map.entry("nursing",new Entity("nursing_notes","patient_id,observation","DOCTOR,NURSE","NURSE","SELECT n.id,n.patient_id,n.observation,n.created_at,u.name AS nurse,p.name AS patient FROM nursing_notes n JOIN users u ON u.id=n.user_id JOIN patients p ON p.id=n.patient_id","patient,nurse,observation"))
    );
    static Entity config(String key) {
        Entity e=ENTITIES.get(key); if(e==null) throw new ApiError(404,"Unknown module"); return e;
    }
    Auth.User permit(HttpServletRequest req,String roles) {
        if(roles.isEmpty()) throw new ApiError(405,"Use the workflow action for this record.");
        return roles.equals("ALL")?auth.require(req):auth.require(req,roles.split(","));
    }
    public Map<String,Object> list(HttpServletRequest req,String key,String q,int page) {
        Entity e=config(key); permit(req,e.readRoles());
        int offset=Math.max(0,Math.min(page,100000))*25;
        String filter=""; var args=new ArrayList<Object>();
        if(q!=null && !q.isBlank()) {
            if(q.length()>120) throw new ApiError(400,"Search is too long.");
            var conditions=new ArrayList<String>();
            for(String field:e.search().split(",")) {conditions.add(field+" LIKE ?");args.add("%"+q.trim()+"%");}
            filter=" WHERE ("+String.join(" OR ",conditions)+")";
        }
        String base="SELECT * FROM ("+e.sql()+") records"+filter;
        return db.read(c -> {
            long total=Db.count(c,"SELECT COUNT(*) FROM ("+base+") matched",args.toArray());
            var paged=new ArrayList<>(args);paged.add(offset);
            return Map.of("rows",Db.query(c,base+" ORDER BY id DESC LIMIT 25 OFFSET ?",paged.toArray()),"total",total,"page",offset/25,"size",25);
        });
    }
    public long save(HttpServletRequest req,String key,Long id,Map<String,Object> body) {
        Entity e=config(key); Auth.User u=permit(req,e.writeRoles());
        var values=validate(key,body);
        return db.tx(c -> {
            if(id!=null) Db.one(c,"SELECT id FROM "+e.table()+" WHERE id=? FOR UPDATE",id);
            if(key.equals("schedules")) {
                Db.one(c,"SELECT id FROM doctors WHERE id=? FOR UPDATE",values.get("doctor_id"));
                if(Db.count(c,"SELECT COUNT(*) FROM doctor_schedules WHERE doctor_id=? AND weekday=? AND start_time<? AND end_time>? AND id<>?",values.get("doctor_id"),values.get("weekday"),values.get("end_time"),values.get("start_time"),id==null?0:id)>0)
                    throw new ApiError(409,"This schedule overlaps an existing schedule.");
            }
            int oldStock=0;
            if(key.equals("medicines") && id!=null) oldStock=((Number)Db.one(c,"SELECT stock FROM medicines WHERE id=?",id).get("stock")).intValue();
            long result;
            if(id==null) {
                if(key.equals("nursing")) values.put("user_id",u.id());
                result=Db.insert(c,"INSERT INTO "+e.table()+" ("+String.join(",",values.keySet())+") VALUES ("+String.join(",",Collections.nCopies(values.size(),"?"))+")",values.values().toArray());
            } else {
                if(key.equals("nursing")) throw new ApiError(405,"Nursing observations are append-only.");
                var args=new ArrayList<>(values.values());args.add(id);
                Db.update(c,"UPDATE "+e.table()+" SET "+String.join(",",values.keySet().stream().map(f->f+"=?").toList())+" WHERE id=?",args.toArray());result=id;
            }
            if(key.equals("medicines")) {
                int change=((Number)values.get("stock")).intValue()-oldStock;
                if(change!=0) Db.insert(c,"INSERT INTO stock_movements(medicine_id,change_qty,reason) VALUES (?,?,?)",result,change,id==null?"Initial stock":"Manual inventory adjustment");
            }
            Db.audit(c,u.id(),id==null?"CREATE":"UPDATE",key,result);return result;
        });
    }
    static LinkedHashMap<String,Object> validate(String key,Map<String,Object> b) {
        var v=new LinkedHashMap<String,Object>();
        switch(key) {
            case "departments" -> v.put("name",Input.text(b,"name",100,true));
            case "patients" -> {
                v.put("name",Input.text(b,"name",120,true));LocalDate date=Input.date(b,"dob");
                if(date.isAfter(LocalDate.now())||date.isBefore(LocalDate.now().minusYears(130)))throw new ApiError(400,"Check the date of birth.");
                v.put("dob",date);v.put("gender",Input.choice(b,"gender","Female","Male","Other","Prefer not to say"));
                v.put("phone",Input.text(b,"phone",30,true));v.put("email",Input.text(b,"email",150,false));
                v.put("address",Input.text(b,"address",500,false));v.put("blood_group",Input.text(b,"blood_group",10,false));v.put("allergies",Input.text(b,"allergies",1000,false));
            }
            case "doctors" -> {
                v.put("name",Input.text(b,"name",120,true));v.put("department_id",Input.id(b,"department_id"));
                v.put("specialization",Input.text(b,"specialization",120,true));v.put("consultation_fee",Input.money(b,"consultation_fee"));v.put("active",Input.bool(b,"active"));
            }
            case "schedules" -> {
                v.put("doctor_id",Input.id(b,"doctor_id"));v.put("weekday",Input.integer(b,"weekday",1,7));
                LocalTime start=Input.time(b,"start_time"),end=Input.time(b,"end_time");
                if(!start.isBefore(end))throw new ApiError(400,"Schedule end must be after its start.");
                v.put("start_time",start);v.put("end_time",end);
            }
            case "tests" -> {v.put("name",Input.text(b,"name",120,true));v.put("price",Input.money(b,"price"));v.put("reference_range",Input.text(b,"reference_range",250,false));}
            case "medicines" -> {
                v.put("name",Input.text(b,"name",120,true));v.put("batch_no",Input.text(b,"batch_no",60,true));v.put("expiry_date",Input.date(b,"expiry_date"));
                v.put("stock",Input.integer(b,"stock",0,10000000));v.put("unit_price",Input.money(b,"unit_price"));v.put("reorder_level",Input.integer(b,"reorder_level",0,10000000));
            }
            case "beds" -> {v.put("ward",Input.text(b,"ward",80,true));v.put("bed_number",Input.text(b,"bed_number",40,true));v.put("daily_rate",Input.money(b,"daily_rate"));}
            case "employees" -> {
                v.put("name",Input.text(b,"name",120,true));v.put("department_id",Input.id(b,"department_id"));v.put("job_title",Input.text(b,"job_title",100,true));
                v.put("phone",Input.text(b,"phone",30,false));v.put("email",Input.text(b,"email",150,false));
            }
            case "attendance" -> {v.put("employee_id",Input.id(b,"employee_id"));v.put("work_date",Input.date(b,"work_date"));v.put("status",Input.choice(b,"status","PRESENT","ABSENT","HALF_DAY"));}
            case "leave" -> {
                v.put("employee_id",Input.id(b,"employee_id"));LocalDate start=Input.date(b,"start_date"),end=Input.date(b,"end_date");
                if(end.isBefore(start))throw new ApiError(400,"Leave end must not precede its start.");
                v.put("start_date",start);v.put("end_date",end);v.put("reason",Input.text(b,"reason",1000,true));v.put("status",Input.choice(b,"status","PENDING","APPROVED","REJECTED"));
            }
            case "nursing" -> {v.put("patient_id",Input.id(b,"patient_id"));v.put("observation",Input.text(b,"observation",4000,true));}
            default -> throw new ApiError(405,"Use a workflow action.");
        }
        return v;
    }
}
