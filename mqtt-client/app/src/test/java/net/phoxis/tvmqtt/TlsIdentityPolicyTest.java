package net.phoxis.tvmqtt;
import org.junit.Test;
import static org.junit.Assert.*;
public class TlsIdentityPolicyTest {
    @Test public void existingAndroid8IdentityKeepsItsCertificate() {
        assertEquals(TlsIdentityPolicy.LEGACY,TlsIdentityPolicy.alias(true,26,false));
    }
    @Test public void modernExistingRestrictedKeyUsesPersistentCompatibleAlias() {
        assertEquals(TlsIdentityPolicy.COMPATIBLE,TlsIdentityPolicy.alias(true,30,false));
    }
    @Test public void freshAndCompatibleKeysAreReused() {
        assertEquals(TlsIdentityPolicy.LEGACY,TlsIdentityPolicy.alias(false,30,false));
        assertEquals(TlsIdentityPolicy.LEGACY,TlsIdentityPolicy.alias(true,30,true));
    }
}
