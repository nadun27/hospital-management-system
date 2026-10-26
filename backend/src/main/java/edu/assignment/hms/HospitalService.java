package edu.assignment.hms;

import org.springframework.stereotype.Service;
import java.sql.Connection;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class HospitalService {
    private final Db db;
    public HospitalService(Db db) {this.db=db;}
    static long num(Map<String,Object> r,String key){return ((Number)r.get(key)).longValue();}
    static BigDecimal amount(Map<String,Object> r,String key){return new BigDecimal(r.get(key).toString());}
    static void check(boolean condition,String message){if(!condition)throw new ApiError(409,message);}
    static void linkedPatient(Connection c,long encounter,long patient) throws Exception {
        check(num(Db.one(c,"SELECT patient_id FROM encounters WHERE id=?",encounter),"patient_id")==patient,"The consultation belongs to another patient.");
    }
    static void charge(Connection c,long patient,String type,long source,String description,BigDecimal value) throws Exception {
        Db.insert(c,"INSERT INTO charges(patient_id,source_type,source_id,description,amount) VALUES (?,?,?,?,?)",patient,type,source,description,value);
    }
    public long appointment(long user,Long id,Map<String,Object>b){
        long patient=Input.id(b,"patient_id"),doctor=Input.id(b,"doctor_id");
        LocalDateTime start=Input.datetime(b,"start_at"),end=Input.datetime(b,"end_at");
        if(!start.isAfter(LocalDateTime.now())||!end.isAfter(start)||!start.toLocalDate().equals(end.toLocalDate()))
            throw new ApiError(400,"Choose a future appointment with an end after its start on the same day.");
        String notes=Input.text(b,"notes",1000,false);
        return db.tx(c->{
            Db.one(c,"SELECT id FROM patients WHERE id=? FOR UPDATE",patient);
            var doctorRow=Db.one(c,"SELECT * FROM doctors WHERE id=? AND active=TRUE FOR UPDATE",doctor);
            if(id!=null)check(Db.one(c,"SELECT status FROM appointments WHERE id=? FOR UPDATE",id).get("status").equals("BOOKED"),"Only booked appointments can be rescheduled.");
            check(Db.count(c,"SELECT COUNT(*) FROM doctor_schedules WHERE doctor_id=? AND weekday=? AND start_time<=? AND end_time>=?",doctor,start.getDayOfWeek().getValue(),start.toLocalTime(),end.toLocalTime())>0,"This time is outside the doctor's schedule.");
            check(Db.count(c,"SELECT COUNT(*) FROM appointments WHERE (doctor_id=? OR patient_id=?) AND status='BOOKED' AND start_at<? AND end_at>? AND id<>?",doctor,patient,end,start,id==null?0:id)==0,"The doctor or patient already has an overlapping appointment.");
            long result;
            if(id==null)result=Db.insert(c,"INSERT INTO appointments(patient_id,doctor_id,start_at,end_at,notes) VALUES (?,?,?,?,?)",patient,doctor,start,end,notes);
            else{Db.update(c,"UPDATE appointments SET patient_id=?,doctor_id=?,start_at=?,end_at=?,notes=? WHERE id=?",patient,doctor,start,end,notes,id);result=id;}
            Db.audit(c,user,id==null?"BOOK":"RESCHEDULE","appointments",result);return result;
        });
    }
    public void cancel(long user,long id){db.tx(c->{
        check(Db.update(c,"UPDATE appointments SET status='CANCELLED' WHERE id=? AND status='BOOKED'",id)==1,"Only booked appointments can be cancelled.");
        Db.audit(c,user,"CANCEL","appointments",id);return null;
    });}
    public long encounter(long user,Map<String,Object>b){
        long patient=Input.id(b,"patient_id"),doctor=Input.id(b,"doctor_id");Long appointment=Input.optionalId(b,"appointment_id");
        String diagnosis=Input.text(b,"diagnosis",2000,true),treatment=Input.text(b,"treatment",4000,false),notes=Input.text(b,"notes",4000,false);
        return db.tx(c->{
            var d=Db.one(c,"SELECT * FROM doctors WHERE id=? AND active=TRUE",doctor);
            if(appointment!=null){var a=Db.one(c,"SELECT * FROM appointments WHERE id=? FOR UPDATE",appointment);
                check(num(a,"patient_id")==patient&&num(a,"doctor_id")==doctor&&a.get("status").equals("BOOKED"),"Choose a booked appointment for this patient and doctor.");}
            long id=Db.insert(c,"INSERT INTO encounters(patient_id,doctor_id,appointment_id,diagnosis,treatment,notes) VALUES (?,?,?,?,?,?)",patient,doctor,appointment,diagnosis,treatment,notes);
            if(appointment!=null)Db.update(c,"UPDATE appointments SET status='COMPLETED' WHERE id=?",appointment);
            charge(c,patient,"CONSULTATION",id,"Consultation: "+d.get("name"),amount(d,"consultation_fee"));
            Db.audit(c,user,"CREATE","encounters",id);return id;
        });
    }
    public long lab(long user,Map<String,Object>b){
        long patient=Input.id(b,"patient_id"),test=Input.id(b,"test_id");Long encounter=Input.optionalId(b,"encounter_id");
        return db.tx(c->{
            if(encounter!=null)linkedPatient(c,encounter,patient);
            var t=Db.one(c,"SELECT * FROM test_catalog WHERE id=?",test);
            long id=Db.insert(c,"INSERT INTO lab_orders(patient_id,encounter_id,test_id) VALUES (?,?,?)",patient,encounter,test);
            charge(c,patient,"LAB",id,"Laboratory: "+t.get("name"),amount(t,"price"));
            Db.audit(c,user,"REQUEST","lab",id);return id;
        });
    }
    public void labAction(long user,long id,Map<String,Object>b){
        String action=Input.choice(b,"action","collect","result");
        String result=action.equals("result")?Input.text(b,"result_text",4000,true):null;
        db.tx(c->{
            var row=Db.one(c,"SELECT * FROM lab_orders WHERE id=? FOR UPDATE",id);
            if(action.equals("collect")){
                check(row.get("status").equals("REQUESTED"),"Only requested tests can be collected.");
                Db.update(c,"UPDATE lab_orders SET status='COLLECTED',collected_at=CURRENT_TIMESTAMP WHERE id=?",id);
            }else{
                check(row.get("status").equals("COLLECTED"),"Collect the sample before entering the result.");
                Db.update(c,"UPDATE lab_orders SET status='COMPLETED',result_text=?,completed_at=CURRENT_TIMESTAMP WHERE id=?",result,id);
            }
            Db.audit(c,user,action.toUpperCase(),"lab",id);return null;
        });
    }
    @SuppressWarnings("unchecked")
    public long prescribe(long user,Map<String,Object>b){
        long patient=Input.id(b,"patient_id"),encounter=Input.id(b,"encounter_id");String notes=Input.text(b,"notes",2000,false);
        if(!(b.get("items") instanceof List<?> raw)||raw.isEmpty()||raw.size()>30)throw new ApiError(400,"Add 1 to 30 prescription items.");
        var items=new ArrayList<Map<String,Object>>();var seen=new HashSet<Long>();
        for(Object value:raw){
            if(!(value instanceof Map<?,?>))throw new ApiError(400,"Invalid prescription item.");
            Map<String,Object> item=(Map<String,Object>)value;long med=Input.id(item,"medicine_id");
            if(!seen.add(med))throw new ApiError(400,"Each medicine batch can appear once per prescription.");
            var clean=new LinkedHashMap<String,Object>();clean.put("medicine_id",med);clean.put("quantity",Input.integer(item,"quantity",1,100000));clean.put("instructions",Input.text(item,"instructions",1000,true));items.add(clean);
        }
        return db.tx(c->{
            linkedPatient(c,encounter,patient);
            for(var item:items){var med=Db.one(c,"SELECT * FROM medicines WHERE id=?",item.get("medicine_id"));
                check(!LocalDate.parse(med.get("expiry_date").toString()).isBefore(LocalDate.now()),"An expired medicine batch cannot be prescribed.");}
            long id=Db.insert(c,"INSERT INTO prescriptions(patient_id,encounter_id,notes) VALUES (?,?,?)",patient,encounter,notes);
            for(var item:items)Db.insert(c,"INSERT INTO prescription_items(prescription_id,medicine_id,quantity,instructions) VALUES (?,?,?,?)",id,item.get("medicine_id"),item.get("quantity"),item.get("instructions"));
            Db.audit(c,user,"PRESCRIBE","prescriptions",id);return id;
        });
    }
    public void dispense(long user,long id){db.tx(c->{
        var rx=Db.one(c,"SELECT * FROM prescriptions WHERE id=? FOR UPDATE",id);
        check(rx.get("status").equals("PENDING"),"This prescription has already been dispensed.");
        var items=Db.query(c,"SELECT * FROM prescription_items WHERE prescription_id=? ORDER BY medicine_id",id);
        check(!items.isEmpty(),"Prescription contains no items.");
        for(var item:items){
            var m=Db.one(c,"SELECT * FROM medicines WHERE id=? FOR UPDATE",item.get("medicine_id"));
            check(!LocalDate.parse(m.get("expiry_date").toString()).isBefore(LocalDate.now()),"Expired batch: "+m.get("name"));
            int qty=((Number)item.get("quantity")).intValue();
            check(num(m,"stock")>=qty,"Insufficient stock: "+m.get("name"));
            Db.update(c,"UPDATE medicines SET stock=stock-? WHERE id=?",qty,m.get("id"));
            Db.insert(c,"INSERT INTO stock_movements(medicine_id,change_qty,reason) VALUES (?,?,?)",m.get("id"),-qty,"Prescription #"+id);
            charge(c,num(rx,"patient_id"),"MEDICINE",num(item,"id"),m.get("name")+" x "+qty,amount(m,"unit_price").multiply(BigDecimal.valueOf(qty)));
        }
        Db.update(c,"UPDATE prescriptions SET status='DISPENSED',dispensed_at=CURRENT_TIMESTAMP WHERE id=?",id);
        Db.audit(c,user,"DISPENSE","prescriptions",id);return null;
    });}
    public long admit(long user,Map<String,Object>b){
        long patient=Input.id(b,"patient_id"),bed=Input.id(b,"bed_id");String notes=Input.text(b,"notes",2000,false);
        return db.tx(c->{
            Db.one(c,"SELECT id FROM patients WHERE id=? FOR UPDATE",patient);
            var room=Db.one(c,"SELECT * FROM beds WHERE id=? FOR UPDATE",bed);
            check(Db.count(c,"SELECT COUNT(*) FROM admissions WHERE (bed_id=? OR patient_id=?) AND discharged_at IS NULL",bed,patient)==0,"The bed is occupied or the patient already has an active admission.");
            long id=Db.insert(c,"INSERT INTO admissions(patient_id,bed_id,daily_rate,notes) VALUES (?,?,?,?)",patient,bed,room.get("daily_rate"),notes);
            Db.audit(c,user,"ADMIT","admissions",id);return id;
        });
    }
    public void discharge(long user,long id){db.tx(c->{
        var a=Db.one(c,"SELECT * FROM admissions WHERE id=? FOR UPDATE",id);
        check(a.get("discharged_at")==null,"This admission is already discharged.");
        LocalDateTime start=LocalDateTime.parse(a.get("admitted_at").toString().replace(' ','T')),end=LocalDateTime.now();
        long days=Math.max(1,(Duration.between(start,end).getSeconds()+86399)/86400);
        charge(c,num(a,"patient_id"),"ADMISSION",id,"Admission #"+id+": "+days+" day(s)",amount(a,"daily_rate").multiply(BigDecimal.valueOf(days)));
        Db.update(c,"UPDATE admissions SET discharged_at=? WHERE id=?",end,id);Db.audit(c,user,"DISCHARGE","admissions",id);return null;
    });}
    public long invoice(long user,Map<String,Object>b){
        long patient=Input.id(b,"patient_id");
        return db.tx(c->{
            Db.one(c,"SELECT id FROM patients WHERE id=? FOR UPDATE",patient);
            var charges=Db.query(c,"SELECT * FROM charges WHERE patient_id=? AND invoice_id IS NULL ORDER BY id FOR UPDATE",patient);
            check(!charges.isEmpty(),"This patient has no unbilled charges.");
            BigDecimal total=charges.stream().map(r->amount(r,"amount")).reduce(BigDecimal.ZERO,BigDecimal::add);
            check(total.compareTo(new BigDecimal("9999999999.99"))<=0,"Invoice total exceeds the supported amount.");
            long id=Db.insert(c,"INSERT INTO invoices(patient_id,total) VALUES (?,?)",patient,total);
            for(var charge:charges){Db.insert(c,"INSERT INTO invoice_items(invoice_id,charge_id,description,amount) VALUES (?,?,?,?)",id,charge.get("id"),charge.get("description"),charge.get("amount"));Db.update(c,"UPDATE charges SET invoice_id=? WHERE id=?",id,charge.get("id"));}
            Db.audit(c,user,"INVOICE","invoices",id);return id;
        });
    }
    public long pay(long user,long invoice,Map<String,Object>b){
        BigDecimal amount=Input.money(b,"amount");String method=Input.choice(b,"method","CASH","CARD","BANK_TRANSFER");
        if(amount.signum()<=0)throw new ApiError(400,"Payment must be greater than zero.");
        return db.tx(c->{
            var bill=Db.one(c,"SELECT * FROM invoices WHERE id=? FOR UPDATE",invoice);
            BigDecimal paid=amount(Db.one(c,"SELECT COALESCE(SUM(amount),0) AS paid FROM payments WHERE invoice_id=?",invoice),"paid");
            check(amount.compareTo(amount(bill,"total").subtract(paid))<=0,"Payment exceeds the outstanding balance.");
            long id=Db.insert(c,"INSERT INTO payments(invoice_id,amount,method) VALUES (?,?,?)",invoice,amount,method);
            Db.audit(c,user,"PAYMENT","payments",id);return id;
        });
    }
    public Map<String,Object> invoiceDetail(long id){return db.read(c->{
        var bill=Db.one(c,"SELECT i.*,p.name AS patient,p.phone FROM invoices i JOIN patients p ON p.id=i.patient_id WHERE i.id=?",id);
        var payments=Db.query(c,"SELECT * FROM payments WHERE invoice_id=? ORDER BY id",id);
        return Map.of("invoice",bill,"items",Db.query(c,"SELECT * FROM invoice_items WHERE invoice_id=? ORDER BY id",id),"payments",payments);
    });}
}
