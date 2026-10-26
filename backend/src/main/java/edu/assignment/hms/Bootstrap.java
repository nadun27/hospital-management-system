package edu.assignment.hms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import java.time.LocalDate;

@Component
public class Bootstrap implements CommandLineRunner {
    private final Db db;
    @Value("${hms.admin-password}") private String password;
    @Value("${hms.seed-demo}") private boolean demo;
    @Value("${hms.demo-password}") private String demoPassword;
    public Bootstrap(Db db) { this.db=db; }
    public void run(String... args) {
        db.tx(c -> {
            if (Db.count(c,"SELECT COUNT(*) FROM users")==0) {
                Db.insert(c,"INSERT INTO users(username,name,password_hash,role) VALUES (?,?,?,?)","admin","System Administrator",Passwords.hash(password),"ADMIN");
            }
            if (demo) {
                for (String role : Auth.ROLES) {
                    String username = role.toLowerCase();
                    if (!role.equals("ADMIN") && Db.count(c,"SELECT COUNT(*) FROM users WHERE username=?",username)==0)
                        Db.insert(c,"INSERT INTO users(username,name,password_hash,role) VALUES (?,?,?,?)",username,"Demo " + role,Passwords.hash(demoPassword),role);
                }
                if (Db.count(c,"SELECT COUNT(*) FROM patients")==0) {
                    long dep=Db.insert(c,"INSERT INTO departments(name) VALUES (?)","General Medicine");
                    long doc=Db.insert(c,"INSERT INTO doctors(name,department_id,specialization,consultation_fee) VALUES (?,?,?,?)","Dr. Maya Perera (fictional)",dep,"General Medicine",2500);
                    for (int day=1;day<=7;day++) Db.insert(c,"INSERT INTO doctor_schedules(doctor_id,weekday,start_time,end_time) VALUES (?,?,?,?)",doc,day,"08:00:00","20:00:00");
                    Db.insert(c,"INSERT INTO patients(name,dob,gender,phone,address,blood_group,allergies) VALUES (?,?,?,?,?,?,?)","Nimali Silva (fictional)","2000-05-12","Female","0700000000","Demo address, Colombo","O+","None reported");
                    Db.insert(c,"INSERT INTO test_catalog(name,price,reference_range) VALUES (?,?,?)","Full Blood Count",1800,"Use the laboratory's validated reference intervals");
                    Db.insert(c,"INSERT INTO medicines(name,batch_no,expiry_date,stock,unit_price,reorder_level) VALUES (?,?,?,?,?,?)","Demo medicine 500 mg","DEMO-001",LocalDate.now().plusYears(2),100,15,20);
                    Db.insert(c,"INSERT INTO beds(ward,bed_number,daily_rate) VALUES (?,?,?)","General Ward","A-01",4000);
                    Db.insert(c,"INSERT INTO employees(name,department_id,job_title,phone) VALUES (?,?,?,?)","Demo Staff Member",dep,"Nurse","0700000001");
                }
            }
            return null;
        });
    }
}
