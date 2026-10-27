package net.phoxis.tvmqtt;

import javax.net.ssl.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.*;
import java.util.*;

public final class TlsSupport {
    private TlsSupport() {}
    // Certificate bypass is an explicit runtime JSON option requested for this LAN.
    @android.annotation.SuppressLint({"TrustAllX509TrustManager","CustomX509TrustManager"})
    public static SSLSocketFactory create(AppConfig config, File directory) throws Exception {
        if (config.insecureTLS) {
            X509TrustManager unchecked = new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            };
            SSLContext uncheckedContext = SSLContext.getInstance("TLS");
            uncheckedContext.init(null, new TrustManager[]{unchecked}, new SecureRandom());
            return uncheckedContext.getSocketFactory();
        }
        List<X509TrustManager> managers = new ArrayList<>();
        if (config.trustSystemCAs) managers.add(manager(null));
        List<Certificate> certificates = new ArrayList<>();
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        if (!config.caPem.isEmpty()) {
            certificates.addAll(factory.generateCertificates(new ByteArrayInputStream(config.caPem.getBytes(StandardCharsets.US_ASCII))));
        }
        if (!config.caFile.isEmpty()) {
            File file = new File(directory, config.caFile);
            if (!file.isFile() || file.length() > 65536) throw new IOException("Custom CA file is missing or too large");
            try (InputStream stream = Files.newInputStream(file.toPath())) {
                certificates.addAll(factory.generateCertificates(stream));
            }
        }
        if (!certificates.isEmpty()) {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null);
            int index = 0;
            for (Certificate certificate : certificates) store.setCertificateEntry("custom-" + index++, certificate);
            managers.add(manager(store));
        }
        if (managers.isEmpty()) throw new CertificateException("No certificate trust anchors configured");
        X509TrustManager union = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {
                List<X509Certificate> issuers = new ArrayList<>();
                for (X509TrustManager m : managers) Collections.addAll(issuers, m.getAcceptedIssuers());
                return issuers.toArray(new X509Certificate[0]);
            }
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                check(chain, authType, true);
            }
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                check(chain, authType, false);
            }
            private void check(X509Certificate[] chain, String authType, boolean client) throws CertificateException {
                for (X509TrustManager m : managers) {
                    try {
                        if (client) m.checkClientTrusted(chain, authType); else m.checkServerTrusted(chain, authType);
                        return;
                    } catch (CertificateException ignored) {}
                }
                throw new CertificateException("Broker certificate is not trusted by configured CAs");
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{union}, new SecureRandom());
        return context.getSocketFactory();
    }
    private static X509TrustManager manager(KeyStore store) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager m : factory.getTrustManagers()) if (m instanceof X509TrustManager) return (X509TrustManager)m;
        throw new CertificateException("No X509 trust manager");
    }
}
