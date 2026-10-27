package net.phoxis.tvmqtt;

import android.security.keystore.*;
import java.io.*;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;
import org.json.JSONObject;

/** AndroidKeyStore persists the private key on the TV; only public material is exported. */
public final class ServerIdentity implements HttpsIdentity {
    private final String alias;
    private final PrivateKey privateKey;
    private final X509Certificate certificate;
    private final SSLContext context;
    private ServerIdentity(PrivateKey key,X509Certificate certificate,String alias) throws Exception {
        this.alias=alias;
        this.privateKey=key; this.certificate=certificate;
        X509ExtendedKeyManager manager=new X509ExtendedKeyManager() {
            public String[] getClientAliases(String type,Principal[] issuers) { return null; }
            public String chooseClientAlias(String[] types,Principal[] issuers,Socket socket) { return null; }
            public String[] getServerAliases(String type,Principal[] issuers) { return type.startsWith("RSA")?new String[]{ServerIdentity.this.alias}:null; }
            public String chooseServerAlias(String type,Principal[] issuers,Socket socket) { return type.startsWith("RSA")?ServerIdentity.this.alias:null; }
            public String chooseEngineServerAlias(String type,Principal[] issuers,SSLEngine engine) { return chooseServerAlias(type,issuers,null); }
            public X509Certificate[] getCertificateChain(String alias) { return ServerIdentity.this.alias.equals(alias)?new X509Certificate[]{ServerIdentity.this.certificate}:null; }
            public PrivateKey getPrivateKey(String alias) { return ServerIdentity.this.alias.equals(alias)?ServerIdentity.this.privateKey:null; }
        };
        context=SSLContext.getInstance("TLS");
        context.init(new KeyManager[]{manager},null,new SecureRandom());
    }
    private static final String ACTIVE_FILE="api-tls-active-alias",PENDING_FILE="api-tls-pending-alias";
    public static synchronized ServerIdentity load(File directory) throws Exception {
        KeyStore store=store();
        String chosen=readAlias(directory,ACTIVE_FILE);
        if(chosen==null || TlsIdentityPolicy.LEGACY.equals(chosen)) {
            boolean legacy=store.containsAlias(TlsIdentityPolicy.LEGACY),raw=false;
            PrivateKey key=legacy?(PrivateKey)store.getKey(TlsIdentityPolicy.LEGACY,null):null;
            if(key!=null && android.os.Build.VERSION.SDK_INT>=28) {
                KeyInfo info=KeyFactory.getInstance("RSA","AndroidKeyStore").getKeySpec(key,KeyInfo.class);
                raw=Arrays.asList(info.getEncryptionPaddings()).contains(KeyProperties.ENCRYPTION_PADDING_NONE);
            }
            chosen=TlsIdentityPolicy.alias(legacy,android.os.Build.VERSION.SDK_INT,raw);
        }
        if(!store.containsAlias(chosen) || store.getKey(chosen,null)==null || store.getCertificate(chosen)==null) {
            if(store.containsAlias(chosen)) store.deleteEntry(chosen);
            generate(chosen);
        }
        ServerIdentity result=identity(store,chosen);
        write(directory,ACTIVE_FILE,chosen);
        write(directory,"api-tls-certificate.pem",result.certificatePem());
        return result;
    }
    /** The inactive slot is replaced; the active listener/key remains usable until Save & apply. */
    public static synchronized String prepareRotation(File directory) throws Exception {
        if(readAlias(directory,PENDING_FILE)!=null) throw new KeyStoreException("A previous HTTPS key is still being applied");
        ServerIdentity current=load(directory);
        String next=TlsIdentityPolicy.nextAlias(current.alias);
        KeyStore store=store();
        if(store.containsAlias(next)) store.deleteEntry(next);
        generate(next);
        identity(store,next); // Verify generation completed before offering its public key.
        return next;
    }
    public static synchronized JSONObject publicInfo(File directory,String candidate) throws Exception {
        return candidate==null?load(directory).publicInfo():identity(store(),candidate).publicInfo();
    }
    public static synchronized void requestActivation(File directory,String candidate) throws Exception {
        if(!TlsIdentityPolicy.rotationAlias(candidate)) throw new KeyStoreException("Invalid HTTPS rotation identity");
        identity(store(),candidate);
        write(directory,PENDING_FILE,candidate);
    }
    /** Invoked only after the previous API and command engine have stopped. */
    public static synchronized void applyPending(File directory) throws Exception {
        String next=readAlias(directory,PENDING_FILE);
        if(next==null) return;
        if(!TlsIdentityPolicy.rotationAlias(next)) throw new KeyStoreException("Invalid HTTPS rotation identity");
        ServerIdentity candidate=identity(store(),next);
        write(directory,ACTIVE_FILE,next);
        write(directory,"api-tls-certificate.pem",candidate.certificatePem());
        new android.util.AtomicFile(new File(directory,PENDING_FILE)).delete();
    }
    private static KeyStore store() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);return store;
    }
    private static ServerIdentity identity(KeyStore store,String alias) throws Exception {
        if(!TlsIdentityPolicy.allowedAlias(alias)) throw new KeyStoreException("Unknown API identity");
        PrivateKey key=(PrivateKey)store.getKey(alias,null);
        X509Certificate certificate=(X509Certificate)store.getCertificate(alias);
        if(key==null || certificate==null) throw new KeyStoreException("Persistent API identity is incomplete");
        certificate.verify(certificate.getPublicKey());
        return new ServerIdentity(key,certificate,alias);
    }
    private static String readAlias(File directory,String name) throws Exception {
        File file=new File(directory,name);
        if(!file.isFile() && !new File(file.getPath()+".bak").isFile()) return null;
        byte[] bytes=new android.util.AtomicFile(file).readFully();
        if(bytes.length>128) throw new KeyStoreException("Invalid API identity record");
        String alias=new String(bytes,StandardCharsets.US_ASCII).trim();
        if(!TlsIdentityPolicy.allowedAlias(alias)) throw new KeyStoreException("Invalid API identity record");
        return alias;
    }
    private static void write(File directory,String name,String value) throws Exception {
        android.util.AtomicFile atomic=new android.util.AtomicFile(new File(directory,name));
        byte[] bytes=value.getBytes(StandardCharsets.US_ASCII);
        // Avoid rewriting persistent identity records during routine QR/public-key reads.
        try { if(Arrays.equals(bytes,atomic.readFully())) return; } catch(FileNotFoundException ignored) {}
        FileOutputStream out=null;
        try { out=atomic.startWrite();out.write(bytes);atomic.finishWrite(out); }
        catch(Exception e) { if(out!=null) atomic.failWrite(out);throw e; }
    }
    private static void generate(String alias) throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");
        long now=System.currentTimeMillis();
        generator.initialize(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY|KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE,KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setUserAuthenticationRequired(false)
                .setCertificateSubject(new X500Principal("CN=TV MQTT & API Client,O=TV Remote"))
                .setCertificateSerialNumber(new BigInteger(128,new SecureRandom()).add(BigInteger.ONE))
                .setCertificateNotBefore(new Date(now-86400000L))
                .setCertificateNotAfter(new Date(now+10L*365*86400000L)).build());
        generator.generateKeyPair();
    }
    public SSLServerSocketFactory socketFactory() { return context.getServerSocketFactory(); }
    public String certificatePem() throws Exception { return pem("CERTIFICATE",certificate.getEncoded()); }
    public JSONObject publicInfo() throws Exception {
        return Jsons.object("algorithm",certificate.getPublicKey().getAlgorithm(),
                "public_key_pem",pem("PUBLIC KEY",certificate.getPublicKey().getEncoded()),
                "public_key_sha256",fingerprint(certificate.getPublicKey().getEncoded()),
                "certificate_pem",certificatePem(),"certificate_sha256",fingerprint(certificate.getEncoded()),
                "not_before",certificate.getNotBefore().toInstant().toString(),
                "not_after",certificate.getNotAfter().toInstant().toString(),
                "storage","AndroidKeyStore","persistent",true,"private_key_exportable",false);
    }
    private static String pem(String type,byte[] bytes) {
        return "-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(bytes)+"\n-----END "+type+"-----\n";
    }
    private static String fingerprint(byte[] bytes) throws Exception {
        StringBuilder result=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(Locale.ROOT,"%02x",b&255));
        return result.toString();
    }
}
