package net.phoxis.tvmqtt;
public final class TlsIdentityPolicy {
    static final String LEGACY="tv-mqtt-api-tls-v1",COMPATIBLE="tv-mqtt-api-tls-v2";
    static final String SLOT_A="tv-mqtt-api-tls-a",SLOT_B="tv-mqtt-api-tls-b";
    static boolean rotationAlias(String alias) { return SLOT_A.equals(alias) || SLOT_B.equals(alias); }
    static boolean allowedAlias(String alias) { return rotationAlias(alias) || LEGACY.equals(alias) || COMPATIBLE.equals(alias); }
    static String nextAlias(String active) { return SLOT_A.equals(active)?SLOT_B:SLOT_A; }
    static String alias(boolean legacyExists,int sdk,boolean rawRsaAllowed) {
        return legacyExists && sdk>=28 && !rawRsaAllowed?COMPATIBLE:LEGACY;
    }
}
