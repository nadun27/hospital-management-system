package edu.assignment.hms;

import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class Queries {
    private final Db db;
    public Queries(Db db){this.db=db;}
    private static boolean allows(String role,String... roles){return role.equals("ADMIN")||Arrays.asList(roles).contains(role);}
    public Map<String,Object> dashboard(String role){return db.read(c->{
        var result=new LinkedHashMap<String,Object>();var cards=new LinkedHashMap<String,Object>();
        if(allows(role,"DOCTOR","NURSE","RECEPTIONIST")){
            cards.put("Registered patients",Db.count(c,"SELECT COUNT(*) FROM patients"));
            LocalDate today=LocalDate.now();
            cards.put("Today's appointments",Db.count(c,"SELECT COUNT(*) FROM appointments WHERE start_at>=? AND start_at<? AND status<>'CANCELLED'",today.atStartOfDay(),today.plusDays(1).atStartOfDay()));
            cards.put("Current inpatients",Db.count(c,"SELECT COUNT(*) FROM admissions WHERE discharged_at IS NULL"));
            result.put("appointments",Db.query(c,"SELECT a.id,p.name AS patient,d.name AS doctor,a.start_at,a.status FROM appointments a JOIN patients p ON p.id=a.patient_id JOIN doctors d ON d.id=a.doctor_id WHERE a.start_at>=? AND a.start_at<? ORDER BY a.start_at LIMIT 12",today.atStartOfDay(),today.plusDays(1).atStartOfDay()));
        }
        if(allows(role,"DOCTOR","LAB","NURSE"))cards.put("Pending laboratory tests",Db.count(c,"SELECT COUNT(*) FROM lab_orders WHERE status<>'COMPLETED'"));
        if(allows(role,"PHARMACIST")){
            cards.put("Pending prescriptions",Db.count(c,"SELECT COUNT(*) FROM prescriptions WHERE status='PENDING'"));
            cards.put("Low stock batches",Db.count(c,"SELECT COUNT(*) FROM medicines WHERE stock<=reorder_level"));
            cards.put("Expired batches",Db.count(c,"SELECT COUNT(*) FROM medicines WHERE expiry_date<?",LocalDate.now()));
            result.put("alerts",Db.query(c,"SELECT name,batch_no,stock,expiry_date FROM medicines WHERE stock<=reorder_level OR expiry_date<? ORDER BY expiry_date LIMIT 20",LocalDate.now().plusDays(31)));
        }
        if(allows(role,"ACCOUNTANT")){
            cards.put("Payments received (LKR)",Db.one(c,"SELECT COALESCE(SUM(amount),0) AS metric FROM payments").get("metric"));
            cards.put("Outstanding balance (LKR)",Db.one(c,"SELECT COALESCE((SELECT SUM(total) FROM invoices),0)-COALESCE((SELECT SUM(amount) FROM payments),0) AS metric").get("metric"));
        }
        result.put("cards",cards);return result;
    });}
    public List<Map<String,Object>> lookup(String role,String kind,String query){
        String q=query==null?"":query.trim();if(q.length()>120)throw new ApiError(400,"Search is too long.");
        String sql;
        switch(kind){
            case "patients" -> sql="SELECT id,name AS label FROM patients";
            case "doctors" -> sql="SELECT id,name AS label FROM doctors WHERE active=TRUE";
            case "departments" -> sql="SELECT id,name AS label FROM departments";
            case "tests" -> {if(!allows(role,"DOCTOR","LAB"))throw new ApiError(403,"Not allowed");sql="SELECT id,name AS label FROM test_catalog";}
            case "medicines" -> {if(!allows(role,"DOCTOR","PHARMACIST"))throw new ApiError(403,"Not allowed");sql="SELECT id,CONCAT(name,' / ',batch_no,' / stock ',stock) AS label FROM medicines";}
            case "beds" -> {if(!allows(role,"RECEPTIONIST","NURSE","DOCTOR"))throw new ApiError(403,"Not allowed");sql="SELECT id,CONCAT(ward,' / ',bed_number) AS label FROM beds";}
            case "employees" -> {if(!allows(role))throw new ApiError(403,"Not allowed");sql="SELECT id,name AS label FROM employees";}
            case "appointments" -> {if(!allows(role,"DOCTOR","RECEPTIONIST"))throw new ApiError(403,"Not allowed");sql="SELECT a.id,CONCAT('#',a.id,' / ',p.name,' / ',a.start_at) AS label FROM appointments a JOIN patients p ON p.id=a.patient_id WHERE a.status='BOOKED'";}
            case "encounters" -> {if(!allows(role,"DOCTOR","LAB","PHARMACIST"))throw new ApiError(403,"Not allowed");sql="SELECT e.id,CONCAT('#',e.id,' / ',p.name,' / ',e.created_at) AS label FROM encounters e JOIN patients p ON p.id=e.patient_id";}
            default -> throw new ApiError(404,"Unknown lookup");
        }
        // Finance and laboratory see patient names/IDs only, never clinical history here.
        return db.read(c->Db.query(c,"SELECT * FROM ("+sql+") options WHERE label LIKE ? ORDER BY id DESC LIMIT 100","%"+q+"%"));
    }
    public Map<String,Object> history(long patient){return db.read(c->{
        var result=new LinkedHashMap<String,Object>();result.put("patient",Db.one(c,"SELECT * FROM patients WHERE id=?",patient));
        result.put("consultations",Db.query(c,"SELECT e.*,d.name AS doctor FROM encounters e JOIN doctors d ON d.id=e.doctor_id WHERE e.patient_id=? ORDER BY e.id DESC",patient));
        result.put("laboratory",Db.query(c,"SELECT l.*,t.name AS test FROM lab_orders l JOIN test_catalog t ON t.id=l.test_id WHERE l.patient_id=? ORDER BY l.id DESC",patient));
        result.put("prescriptions",Db.query(c,"SELECT r.id,r.created_at,r.status,m.name,i.quantity,i.instructions FROM prescriptions r JOIN prescription_items i ON i.prescription_id=r.id JOIN medicines m ON m.id=i.medicine_id WHERE r.patient_id=? ORDER BY r.id DESC",patient));
        result.put("nursing",Db.query(c,"SELECT n.created_at,n.observation,u.name AS nurse FROM nursing_notes n JOIN users u ON u.id=n.user_id WHERE n.patient_id=? ORDER BY n.id DESC",patient));return result;
    });}
    public Map<String,Object> reports(String role){return db.read(c->{
        var reports=new LinkedHashMap<String,Object>();
        if(allows(role,"DOCTOR","NURSE","RECEPTIONIST")){
            reports.put("Patients by gender",Db.query(c,"SELECT gender,COUNT(*) AS patients FROM patients GROUP BY gender"));
            reports.put("Appointments by status",Db.query(c,"SELECT status,COUNT(*) AS appointments FROM appointments GROUP BY status"));
        }
        if(allows(role,"ACCOUNTANT"))reports.put("Payments by day (LKR)",Db.query(c,"SELECT CAST(created_at AS DATE) AS payment_date,SUM(amount) AS received FROM payments GROUP BY CAST(created_at AS DATE) ORDER BY payment_date DESC LIMIT 90"));
        if(allows(role,"PHARMACIST"))reports.put("Pharmacy stock",Db.query(c,"SELECT name,batch_no,stock,reorder_level,expiry_date FROM medicines ORDER BY name LIMIT 500"));
        if(allows(role,"DOCTOR","LAB"))reports.put("Laboratory workload",Db.query(c,"SELECT t.name AS test,l.status,COUNT(*) AS requests FROM lab_orders l JOIN test_catalog t ON t.id=l.test_id GROUP BY t.name,l.status"));
        if(allows(role))reports.put("Staff attendance",Db.query(c,"SELECT e.name AS employee,a.status,COUNT(*) AS days FROM attendance a JOIN employees e ON e.id=a.employee_id GROUP BY e.name,a.status"));
        return reports;
    });}
}
