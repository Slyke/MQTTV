package net.phoxis.tvmqtt;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class BrowserPhoneTest {
    private AppConfig options(boolean http,boolean https,String bind) throws Exception {
        return new AppConfig(Jsons.object("mqtt",Jsons.object("uri","mqtt://broker","clientId","phone"),
                "tv",Jsons.object("driver","nvidia-shield"),
                "api",Jsons.object("passkey","key:+/?","bindAddress",bind,
                        "http",Jsons.object("enabled",http,"port",8788),"https",Jsons.object("enabled",https,"port",8789))));
    }
    @Test public void phoneLinkPrefersHttpsAndKeepsPasskeyInFragmentOnly() throws Exception {
        URI link=new URI(PhoneLink.url(options(true,true,"0.0.0.0"),"192.168.1.50"));
        assertEquals("https",link.getScheme());assertEquals(8789,link.getPort());
        assertNull(link.getRawQuery());
        assertEquals("key:+/?",URLDecoder.decode(link.getRawFragment().substring(4),"UTF-8"));
    }
    @Test public void httpAndIpv6LinksUseTheSelectedListener() throws Exception {
        assertTrue(PhoneLink.url(options(true,false,"0.0.0.0"),"192.168.1.50").startsWith("http://192.168.1.50:8788/"));
        assertTrue(PhoneLink.url(options(false,true,"::"),"fd00::12").startsWith("https://[fd00::12]:8789/"));
    }
    @Test public void disabledListenersHideThePhoneActionAndLoopbackCannotBeShared() throws Exception {
        assertNull(PhoneLink.url(options(false,false,"0.0.0.0"),"192.168.1.50"));
        for(String invalid:new String[]{"127.0.0.1","::1","localhost","0.0.0.0"}) {
            try { PhoneLink.url(options(true,false,invalid),invalid);fail(); }
            catch(IllegalArgumentException expected) {}
        }
    }
    @Test public void newSetupsUseHttpAndExistingListenerChoicesArePreserved() throws Exception {
        JSONObject fresh=ConfigEditor.initial(null,"new-device","nvidia-shield");
        fresh.getJSONObject("mqtt").put("uri","mqtt://broker");
        AppConfig config=new AppConfig(fresh);assertTrue(config.apiHttpEnabled);assertFalse(config.apiHttpsEnabled);
        JSONObject existing=Jsons.object("api",Jsons.object("https",Jsons.object("enabled",true),"http",Jsons.object("enabled",false)));
        JSONObject draft=ConfigEditor.initial(existing,"id","nvidia-shield");
        assertTrue(draft.getJSONObject("api").getJSONObject("https").getBoolean("enabled"));
        assertFalse(draft.getJSONObject("api").getJSONObject("http").getBoolean("enabled"));
    }
    @Test public void omittedListenAddressUsesAllInterfacesAndQrTracksDhcp() throws Exception {
        AppConfig config=new AppConfig(Jsons.object("mqtt",Jsons.object("uri","mqtt://broker","clientId","tv"),
                "tv",Jsons.object("driver","nvidia-shield"),"api",Jsons.object("passkey","fixture",
                        "http",Jsons.object("enabled",true))));
        assertEquals("0.0.0.0",config.apiBindAddress);
        assertTrue(PhoneLink.url(config,"192.168.1.50").startsWith("http://192.168.1.50:8788/"));
        assertTrue(PhoneLink.url(config,"192.168.1.75").startsWith("http://192.168.1.75:8788/"));
    }
    @Test public void oldAdbSettingsDoNotSelectDeveloperFallback() throws Exception {
        JSONObject draft=ConfigEditor.initial(null,"tv","nvidia-shield");draft.getJSONObject("mqtt").put("uri","mqtt://broker");
        draft.getJSONObject("tv").put("adb",Jsons.object("enabled",true,"keyFile","legacy.pem"));
        assertFalse(new AppConfig(draft).textAdbEnabled);assertEquals("native",new AppConfig(draft).tvControlMethod);
        draft.getJSONObject("tv").put("controlMethod","adb");
        assertTrue(new AppConfig(draft).textAdbEnabled);assertEquals("legacy.pem",new AppConfig(draft).textAdbKeyFile);
        draft.getJSONObject("tv").put("controlMethod","unknown");
        try { new AppConfig(draft);fail(); } catch(IllegalArgumentException expected) {}
    }
    @Test public void browserSessionsExpireAndApiKeyRotationInvalidatesThem() throws Exception {
        BrowserSessions first=new BrowserSessions("first-test-key");String token=first.issue(1000);
        assertTrue(first.valid(token,1001));assertFalse(first.valid(token,1000+BrowserSessions.LIFETIME));
        assertFalse(new BrowserSessions("rotated-test-key").valid(token,1001));
        assertFalse(first.valid(token+"x",1001));assertFalse(first.valid(token.replace('.',':'),1001));
        assertNotEquals(token,first.issue(1000));assertFalse(token.contains("first-test-key"));
    }
    @Test public void cookieSecurityAndProtocolSwitchingAreIndependent() throws Exception {
        BrowserSessions sessions=new BrowserSessions("test-key");
        String http=sessions.cookie(false,1000),https=sessions.cookie(true,1000);
        assertTrue(http.contains("HttpOnly"));assertTrue(http.contains("SameSite=Strict"));
        assertFalse(http.contains("; Secure"));assertTrue(https.contains("; Secure"));
        assertTrue(sessions.authorized(http,1001,false));assertTrue(sessions.authorized(https,1001,true));
        assertFalse(sessions.authorized(https,1001,false));assertFalse(sessions.authorized(http,1001,true));
        assertFalse(sessions.authorized(http+"; "+http,1001,false));
    }
    @Test public void browserOriginMustMatchTheListenerExactly() {
        assertTrue(BrowserSessions.sameOrigin("http://192.168.1.50:8788","192.168.1.50:8788",false));
        for(String origin:new String[]{"null","https://192.168.1.50:8788","http://evil.invalid","http://192.168.1.50:8789",
                "http://user@192.168.1.50:8788","http://192.168.1.50:8788/path","http://192.168.1.50:8788#key"})
            assertFalse(BrowserSessions.sameOrigin(origin,"192.168.1.50:8788",false));
    }
}
