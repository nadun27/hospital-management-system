package edu.assignment.hms;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:hms;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","hms.admin-password=TestAdmin!2026","hms.seed-demo=false"})
@AutoConfigureMockMvc
class HospitalIntegrationTest {
    @Autowired Db db;@Autowired HospitalService service;@Autowired DataSource dataSource;@Autowired MockMvc mvc;@Autowired ObjectMapper json;
    long user,patient,doctor,medicine,encounter;String tomorrow;
    @BeforeEach void seed() throws Exception {
        try(var c=dataSource.getConnection();var s=c.createStatement()){s.execute("DROP ALL OBJECTS");}
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        db.tx(c->{
            user=Db.insert(c,"INSERT INTO users(username,name,password_hash,role) VALUES (?,?,?,?)","admin","Test Admin",Passwords.hash("TestAdmin!2026"),"ADMIN");
            long dep=Db.insert(c,"INSERT INTO departments(name) VALUES (?)","General");
            patient=Db.insert(c,"INSERT INTO patients(name,dob,gender,phone) VALUES (?,?,?,?)","Test Patient","2000-01-01","Female","0700000000");
            doctor=Db.insert(c,"INSERT INTO doctors(name,department_id,specialization,consultation_fee) VALUES (?,?,?,?)","Test Doctor",dep,"General",2500);
            for(int day=1;day<=7;day++)Db.insert(c,"INSERT INTO doctor_schedules(doctor_id,weekday,start_time,end_time) VALUES (?,?,?,?)",doctor,day,"08:00","20:00");
            medicine=Db.insert(c,"INSERT INTO medicines(name,batch_no,expiry_date,stock,unit_price,reorder_level) VALUES (?,?,?,?,?,?)","Demo medicine","B1",LocalDate.now().plusYears(1),20,10,5);
            encounter=Db.insert(c,"INSERT INTO encounters(patient_id,doctor_id,diagnosis) VALUES (?,?,?)",patient,doctor,"Test entry");return null;
        });tomorrow=LocalDate.now().plusDays(1).toString();
    }
    Map<String,Object> appointment(String start,String end){return Map.of("patient_id",patient,"doctor_id",doctor,"start_at",tomorrow+"T"+start,"end_at",tomorrow+"T"+end);}
    long prescription(int quantity){return service.prescribe(user,Map.of("patient_id",patient,"encounter_id",encounter,"items",List.of(Map.of("medicine_id",medicine,"quantity",quantity,"instructions","Test instructions"))));}
    @Test void overlappingAppointmentsAreRejectedButAdjacentSlotsWork(){
        long id=service.appointment(user,null,appointment("09:00","09:30"));
        assertEquals(409,assertThrows(ApiError.class,()->service.appointment(user,null,appointment("09:15","09:45"))).status);
        assertTrue(service.appointment(user,null,appointment("09:30","10:00"))>0);
        service.cancel(user,id);assertTrue(service.appointment(user,null,appointment("09:00","09:30"))>0);
    }
    @Test void appointmentMustBeWithinSchedule(){assertThrows(ApiError.class,()->service.appointment(user,null,appointment("07:00","07:30")));}
    @Test void completeWorkflowCreatesAccurateInvoiceAndRejectsOverpayment(){
        long a=service.appointment(user,null,appointment("10:00","10:30"));
        long visit=service.encounter(user,Map.of("patient_id",patient,"doctor_id",doctor,"appointment_id",a,"diagnosis","Assignment example"));
        long test=db.tx(c->Db.insert(c,"INSERT INTO test_catalog(name,price) VALUES (?,?)","Demo test",1000));
        long lab=service.lab(user,Map.of("patient_id",patient,"encounter_id",visit,"test_id",test));
        service.labAction(user,lab,Map.of("action","collect"));service.labAction(user,lab,Map.of("action","result","result_text","Demo result"));
        long rx=service.prescribe(user,Map.of("patient_id",patient,"encounter_id",visit,"items",List.of(Map.of("medicine_id",medicine,"quantity",5,"instructions","Demo only"))));
        service.dispense(user,rx);long invoice=service.invoice(user,Map.of("patient_id",patient));
        var bill=service.invoiceDetail(invoice);assertEquals("3550.00",((Map<?,?>)bill.get("invoice")).get("total").toString());
        service.pay(user,invoice,Map.of("amount",1000,"method","CASH"));
        assertThrows(ApiError.class,()->service.pay(user,invoice,Map.of("amount",2550.01,"method","CASH")));
        service.pay(user,invoice,Map.of("amount",2550,"method","BANK_TRANSFER"));
        assertThrows(ApiError.class,()->service.invoice(user,Map.of("patient_id",patient)));
        assertEquals(15,db.read(c->Db.count(c,"SELECT stock FROM medicines WHERE id=?",medicine)).intValue());
    }
    @Test void insufficientStockRollsBackEveryItemAndCharge(){
        long second=db.tx(c->Db.insert(c,"INSERT INTO medicines(name,batch_no,expiry_date,stock,unit_price,reorder_level) VALUES (?,?,?,?,?,?)","Second demo batch","B2",LocalDate.now().plusYears(1),1,10,5));
        long rx=service.prescribe(user,Map.of("patient_id",patient,"encounter_id",encounter,"items",List.of(Map.of("medicine_id",medicine,"quantity",2,"instructions","Demo"),Map.of("medicine_id",second,"quantity",30,"instructions","Demo"))));
        assertThrows(ApiError.class,()->service.dispense(user,rx));
        assertEquals(20,db.read(c->Db.count(c,"SELECT stock FROM medicines WHERE id=?",medicine)).intValue());
        assertEquals(0,db.read(c->Db.count(c,"SELECT COUNT(*) FROM charges")).intValue());
    }
    @Test void expiredBatchAndDoubleDispensingAreRejected(){
        long rx=prescription(2);db.tx(c->{Db.update(c,"UPDATE medicines SET expiry_date=? WHERE id=?",LocalDate.now().minusDays(1),medicine);return null;});
        assertThrows(ApiError.class,()->service.dispense(user,rx));
        db.tx(c->{Db.update(c,"UPDATE medicines SET expiry_date=? WHERE id=?",LocalDate.now().plusDays(1),medicine);return null;});
        service.dispense(user,rx);assertThrows(ApiError.class,()->service.dispense(user,rx));
    }
    @Test void labResultRequiresSampleCollection(){
        long test=db.tx(c->Db.insert(c,"INSERT INTO test_catalog(name,price) VALUES (?,?)","T1",150));
        long lab=service.lab(user,Map.of("patient_id",patient,"test_id",test));
        assertThrows(ApiError.class,()->service.labAction(user,lab,Map.of("action","result","result_text","Result")));
    }
    @Test void occupiedBedAndRepeatedDischargeAreRejected(){
        long bed=db.tx(c->Db.insert(c,"INSERT INTO beds(ward,bed_number,daily_rate) VALUES (?,?,?)","General","A1",4000));
        long admission=service.admit(user,Map.of("patient_id",patient,"bed_id",bed));
        assertThrows(ApiError.class,()->service.admit(user,Map.of("patient_id",patient,"bed_id",bed)));
        service.discharge(user,admission);assertThrows(ApiError.class,()->service.discharge(user,admission));
        assertEquals("4000.00",db.read(c->Db.one(c,"SELECT amount FROM charges WHERE source_type='ADMISSION'")).get("amount").toString());
    }
    @Test void authenticationCsrfAndRoleRestrictionsAreEnforced() throws Exception {
        mvc.perform(get("/api/records/patients")).andExpect(status().isUnauthorized());
        var login=mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"admin\",\"password\":\"TestAdmin!2026\"}")).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)login.getRequest().getSession(false);String csrf=json.readTree(login.getResponse().getContentAsString()).get("csrf").asText();
        mvc.perform(post("/api/records/departments").session(session).contentType("application/json").content("{\"name\":\"Surgery\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/records/departments").session(session).header("X-CSRF-Token",csrf).contentType("application/json").content("{\"name\":\"Surgery\"}")).andExpect(status().isOk());
        db.tx(c->{Db.update(c,"UPDATE users SET role='ACCOUNTANT' WHERE id=?",user);return null;});
        mvc.perform(get("/api/patients/"+patient+"/history").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/api/records/invoices").session(session)).andExpect(status().isOk());
    }
    @Test void passwordHashUsesRandomSalt(){String a=Passwords.hash("SamePassword!2026"),b=Passwords.hash("SamePassword!2026");assertNotEquals(a,b);assertTrue(Passwords.matches("SamePassword!2026",a));assertFalse(Passwords.matches("WrongPassword",a));}
    @Test void simultaneousBookingAllowsOnlyOneRequest() throws Exception {
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);var gate=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> book=()->{gate.await();try{service.appointment(user,null,appointment("11:00","11:30"));return true;}catch(ApiError e){assertEquals(409,e.status);return false;}};
            var first=executor.submit(book);var second=executor.submit(book);gate.countDown();
            assertNotEquals(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,db.read(c->Db.count(c,"SELECT COUNT(*) FROM appointments WHERE status='BOOKED'")).intValue());
        } finally {executor.shutdownNow();}
    }
    @Test void dashboardReportsListsLookupsAndDocumentsWork() throws Exception {
        var login=mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"admin\",\"password\":\"TestAdmin!2026\"}")).andExpect(status().isOk()).andReturn();
        var session=(MockHttpSession)login.getRequest().getSession(false);String csrf=json.readTree(login.getResponse().getContentAsString()).get("csrf").asText();
        mvc.perform(get("/api/dashboard").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/reports").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/calendar").param("month",YearMonth.now().toString()).session(session)).andExpect(status().isOk());
        for(String key:Catalog.ENTITIES.keySet())mvc.perform(get("/api/records/"+key).param("q","Test").session(session)).andExpect(status().isOk());
        for(String key:List.of("patients","doctors","departments","tests","medicines","beds","employees","appointments","encounters"))mvc.perform(get("/api/lookups/"+key).session(session)).andExpect(status().isOk());
        var file=new org.springframework.mock.web.MockMultipartFile("file","demo.pdf","application/pdf","%PDF-1.4 demo".getBytes());
        var upload=mvc.perform(multipart("/api/patients/"+patient+"/documents").file(file).session(session).header("X-CSRF-Token",csrf)).andExpect(status().isOk()).andReturn();
        long doc=json.readTree(upload.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(get("/api/documents/"+doc).session(session)).andExpect(status().isOk()).andExpect(content().bytes("%PDF-1.4 demo".getBytes()));
    }
}
