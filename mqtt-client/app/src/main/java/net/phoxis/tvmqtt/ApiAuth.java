package net.phoxis.tvmqtt;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class ApiAuth {
    public static final String HEADER="X-API-Key";
    private ApiAuth() {}
    public static boolean matches(String expected,String supplied) {
        if(expected==null || expected.isEmpty() || supplied==null || supplied.length()>512) return false;
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            return MessageDigest.isEqual(hash.digest(expected.getBytes(StandardCharsets.UTF_8)),
                    hash.digest(supplied.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception impossible) { return false; }
    }
}
