package edu.assignment.hms;

import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class Auth {
    public static final String[] ROLES = {"ADMIN","DOCTOR","NURSE","RECEPTIONIST","LAB","PHARMACIST","ACCOUNTANT"};
    private final Db db;
    private final String allowedOrigin;
    private final String dummy = Passwords.hash("not-a-real-password");
    private record Attempt(int count, long window) {}
    private final ConcurrentHashMap<String,Attempt> attempts = new ConcurrentHashMap<>();
    public record User(long id, String username, String name, String role, int version) {}
    public Auth(Db db, @Value("${hms.allowed-origin}") String origin) { this.db = db; this.allowedOrigin = origin; }
    public void origin(HttpServletRequest req) {
        String o = req.getHeader("Origin");
        String self = req.getScheme() + "://" + req.getServerName() + ((req.getServerPort()==80 || req.getServerPort()==443)? "" : ":"+req.getServerPort());
        if (o != null && !o.equals(allowedOrigin) && !o.equals(self)) throw new ApiError(403,"Origin is not allowed.");
        if ("cross-site".equals(req.getHeader("Sec-Fetch-Site"))) throw new ApiError(403,"Cross-site request is not allowed.");
    }
    public User require(HttpServletRequest req, String... roles) {
        HttpSession s = req.getSession(false);
        User u = s == null ? null : (User)s.getAttribute("user");
        if (u == null) throw new ApiError(401,"Please log in.");
        // Re-read role/active flag so account changes take effect immediately.
        var current = db.read(c -> Db.one(c,"SELECT id,username,name,role,active,auth_version FROM users WHERE id=?",u.id()));
        if (((Number)current.get("auth_version")).intValue() != u.version()) {s.invalidate();throw new ApiError(401,"Your password changed. Log in again.");}
        if (!Boolean.TRUE.equals(current.get("active")) && !"1".equals(String.valueOf(current.get("active")))) {
            s.invalidate(); throw new ApiError(401,"Account disabled.");
        }
        User fresh = new User(u.id(),current.get("username").toString(),current.get("name").toString(),current.get("role").toString(),u.version());
        if (roles.length > 0 && !fresh.role().equals("ADMIN") && !Arrays.asList(roles).contains(fresh.role()))
            throw new ApiError(403,"Your role cannot perform this action.");
        if (!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())) {
            origin(req);
            if (!Objects.equals(s.getAttribute("csrf"),req.getHeader("X-CSRF-Token"))) throw new ApiError(403,"Invalid security token. Log in again.");
        }
        return fresh;
    }
    public Map<String,Object> login(HttpServletRequest req, Map<String,Object> b) {
        origin(req);
        String username = Input.text(b,"username",60,true), password = Input.password(b,"password",true);
        String key = req.getRemoteAddr(); long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e -> now-e.getValue().window() > 900_000);
        Attempt a = attempts.compute(key,(k,v)->v==null?new Attempt(1,now):new Attempt(v.count()+1,v.window()));
        if (a.count()>20) throw new ApiError(429,"Too many login attempts. Try again in 15 minutes.");
        var rows = db.read(c -> Db.query(c,"SELECT * FROM users WHERE username=? AND active=TRUE",username));
        String hash = rows.isEmpty()?dummy:rows.get(0).get("password_hash").toString();
        if (!Passwords.matches(password,hash) || rows.isEmpty()) throw new ApiError(401,"Incorrect username or password.");
        attempts.remove(key);
        var r = rows.get(0); User u = new User(((Number)r.get("id")).longValue(),username,r.get("name").toString(),r.get("role").toString(),((Number)r.get("auth_version")).intValue());
        HttpSession old = req.getSession(false); if (old != null) old.invalidate();
        HttpSession s = req.getSession(true); s.setAttribute("user",u); s.setAttribute("csrf",Passwords.token());
        db.tx(c -> { Db.audit(c,u.id(),"LOGIN","users",u.id()); return null; });
        return Map.of("user",u,"csrf",s.getAttribute("csrf"));
    }
}
