package net.phoxis.tvmqtt;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.security.*;
import java.util.*;
import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.QRCodeReader;

public class CredentialsQrTest {
    @Test public void missingAndEmptyApiPasskeysAreGeneratedWithoutChangingOtherCredentials() throws Exception {
        for(Object value:new Object[]{null,JSONObject.NULL,""}) {
            JSONObject config=Jsons.object("mqtt",Jsons.object("password","broker-manual"),
                    "tv",Jsons.object("preSharedKey","tv-manual"));
            if(value!=null) config.put("api",Jsons.object("passkey",value));
            assertTrue(Credentials.ensurePasskey(config));
            assertTrue(config.getJSONObject("api").getString("passkey").matches("[A-Za-z0-9_-]{43}"));
            assertEquals("broker-manual",config.getJSONObject("mqtt").getString("password"));
            assertEquals("tv-manual",config.getJSONObject("tv").getString("preSharedKey"));
        }
    }
    @Test public void existingApiPasskeySurvivesEditorOpeningAndMigration() throws Exception {
        JSONObject config=Jsons.object("api",Jsons.object("passkey","existing-key"));
        assertFalse(Credentials.ensurePasskey(config));
        assertEquals("existing-key",ConfigEditor.initial(config,"tv-id","nvidia-shield").getJSONObject("api").getString("passkey"));
    }
    @Test public void generatedKeysHave256BitsOfUrlSafeRandomMaterial() {
        Set<String> values=new HashSet<>();
        for(int i=0;i<100;i++) {
            String key=Credentials.generatePasskey();
            assertEquals(32,Base64.getUrlDecoder().decode(key).length);assertFalse(key.contains("="));
            assertTrue(values.add(key));
        }
    }
    @Test public void apiPasskeyQrDecodesToOnlyTheExactPasskey() throws Exception {
        String key=Credentials.generatePasskey();assertEquals(key,decode(QrCodes.encode(key)));
    }
    @Test public void publicKeyQrPreservesPemNewlinesAndDoesNotContainPrivateMaterial() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        String pem="-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{10})
                .encodeToString(generator.generateKeyPair().getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n";
        String decoded=decode(QrCodes.encode(pem));
        assertEquals(pem,decoded);assertFalse(decoded.contains("PRIVATE KEY"));
    }
    @Test public void largerCertificateAndUnicodePasswordQrRoundTripExactly() throws Exception {
        String certificate="-----BEGIN CERTIFICATE-----\n"+
                new String(new char[1100]).replace('\0','a')+"\n-----END CERTIFICATE-----\n";
        assertEquals(certificate,decode(QrCodes.encode(certificate)));
        assertEquals("p\u00e4ss word:/+#",decode(QrCodes.encode("p\u00e4ss word:/+#")));
    }
    @Test public void emptyQrPayloadIsRejected() throws Exception {
        for(String value:new String[]{null,""}) {
            try { QrCodes.encode(value);fail(); } catch(IllegalArgumentException expected) {}
        }
    }
    @Test public void rotationSlotsNeverOverwriteActiveKeysOrAcceptArbitraryAliases() {
        assertEquals(TlsIdentityPolicy.SLOT_B,TlsIdentityPolicy.nextAlias(TlsIdentityPolicy.SLOT_A));
        assertEquals(TlsIdentityPolicy.SLOT_A,TlsIdentityPolicy.nextAlias(TlsIdentityPolicy.SLOT_B));
        assertEquals(TlsIdentityPolicy.SLOT_A,TlsIdentityPolicy.nextAlias(TlsIdentityPolicy.LEGACY));
        for(String invalid:new String[]{null,"other-app-key","../key",""})
            assertFalse(TlsIdentityPolicy.allowedAlias(invalid));
        assertFalse(TlsIdentityPolicy.rotationAlias(TlsIdentityPolicy.LEGACY));
        assertTrue(TlsIdentityPolicy.rotationAlias(TlsIdentityPolicy.SLOT_A));
    }
    private static String decode(BitMatrix matrix) throws Exception {
        int scale=6,size=matrix.getWidth()*scale;
        byte[] luminance=new byte[size*size];
        for(int y=0;y<size;y++) for(int x=0;x<size;x++)
            luminance[y*size+x]=(byte)(matrix.get(x/scale,y/scale)?0:255);
        LuminanceSource source=new LuminanceSource(size,size) {
            public byte[] getRow(int y,byte[] row) { return Arrays.copyOfRange(luminance,y*size,(y+1)*size); }
            public byte[] getMatrix() { return luminance; }
        };
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    }
}
