package edu.assignment.hms;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class Input {
    private Input() {}
    public static String password(Map<String,Object> b,String key,boolean required) {
        Object value=b.get(key);String s=value==null?"":value.toString();
        if(s.length()>200||(required&&s.isEmpty()))throw new ApiError(400,"Check "+key.replace('_',' ')+".");
        return s.isEmpty()?null:s;
    }
    public static String text(Map<String,Object> b, String key, int max, boolean required) {
        Object raw = b.get(key); String s = raw == null ? "" : raw.toString().trim();
        if ((required && s.isEmpty()) || s.length() > max) throw new ApiError(400,"Check " + key.replace('_',' ') + ".");
        return s.isEmpty() ? null : s;
    }
    public static long id(Map<String,Object> b, String key) {
        try { long n = Long.parseLong(String.valueOf(b.get(key))); if (n > 0) return n; }
        catch (NumberFormatException ignored) {} throw new ApiError(400,"Select a valid " + key.replace('_',' ') + ".");
    }
    public static Long optionalId(Map<String,Object> b, String key) {
        return b.get(key) == null || b.get(key).toString().isBlank() ? null : id(b,key);
    }
    public static int integer(Map<String,Object> b, String key, int min, int max) {
        try { int n = Integer.parseInt(String.valueOf(b.get(key))); if (n >= min && n <= max) return n; }
        catch (NumberFormatException ignored) {} throw new ApiError(400,"Check " + key.replace('_',' ') + ".");
    }
    public static BigDecimal money(Map<String,Object> b, String key) {
        try {
            BigDecimal n = new BigDecimal(String.valueOf(b.get(key)));
            if (n.signum() >= 0 && n.compareTo(new BigDecimal("9999999999.99")) <= 0 && n.scale() <= 2) return n.setScale(2);
        } catch (NumberFormatException ignored) {} throw new ApiError(400,"Check " + key.replace('_',' ') + ": use a nonnegative amount with up to two decimal places.");
    }
    public static String choice(Map<String,Object> b, String key, String... choices) {
        String s = text(b,key,50,true);
        if (!Arrays.asList(choices).contains(s)) throw new ApiError(400,"Invalid " + key.replace('_',' ') + ".");
        return s;
    }
    public static LocalDate date(Map<String,Object> b, String key) {
        try { return LocalDate.parse(String.valueOf(b.get(key))); }
        catch (Exception e) { throw new ApiError(400,"Check " + key.replace('_',' ') + "."); }
    }
    public static LocalTime time(Map<String,Object> b, String key) {
        try { return LocalTime.parse(String.valueOf(b.get(key))); }
        catch (Exception e) { throw new ApiError(400,"Check " + key.replace('_',' ') + "."); }
    }
    public static LocalDateTime datetime(Map<String,Object> b, String key) {
        try { return LocalDateTime.parse(String.valueOf(b.get(key)).replace(' ','T')); }
        catch (Exception e) { throw new ApiError(400,"Check " + key.replace('_',' ') + "."); }
    }
    public static boolean bool(Map<String,Object> b, String key) {
        Object v = b.get(key);
        if (v instanceof Boolean x) return x;
        if ("1".equals(String.valueOf(v)) || "true".equals(String.valueOf(v))) return true;
        if ("0".equals(String.valueOf(v)) || "false".equals(String.valueOf(v))) return false;
        throw new ApiError(400,"Check " + key + ".");
    }
}
