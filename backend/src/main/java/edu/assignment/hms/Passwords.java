package edu.assignment.hms;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.util.Base64;

public final class Passwords {
    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private Passwords() {}
    public static String token() { byte[] b = new byte[32]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static byte[] derive(String password, byte[] salt, int iterations) {
        var spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
        finally { spec.clearPassword(); }
    }
    public static String hash(String password) {
        if (password == null || password.length() < 10 || password.length() > 200)
            throw new ApiError(400,"Password must contain 10 to 200 characters.");
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        return ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(derive(password,salt,ITERATIONS));
    }
    public static boolean matches(String password, String hash) {
        try {
            String[] parts = hash.split("\\$");
            return MessageDigest.isEqual(Base64.getDecoder().decode(parts[2]), derive(password,Base64.getDecoder().decode(parts[1]),Integer.parseInt(parts[0])));
        } catch (RuntimeException e) { return false; }
    }
}
