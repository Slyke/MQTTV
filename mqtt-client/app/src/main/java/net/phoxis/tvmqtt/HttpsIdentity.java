package net.phoxis.tvmqtt;
import org.json.JSONObject;
import javax.net.ssl.SSLServerSocketFactory;

public interface HttpsIdentity {
    SSLServerSocketFactory socketFactory() throws Exception;
    JSONObject publicInfo() throws Exception;
    String certificatePem() throws Exception;
    interface Provider { HttpsIdentity load() throws Exception; }
}
