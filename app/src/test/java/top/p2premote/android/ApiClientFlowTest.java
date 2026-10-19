package top.p2premote.android;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class ApiClientFlowTest {
    static final class Connection extends HttpURLConnection {
        final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        String response; int status=200; long length=-1; boolean disconnected;
        IOException failure; Runnable beforeResponse;
        Connection(String response) throws Exception {super(new URL("https://unit.invalid"));this.response=response;}
        @Override public void connect() {}
        @Override public boolean usingProxy(){return false;}
        @Override public void disconnect(){disconnected=true;}
        @Override public OutputStream getOutputStream(){return sent;}
        @Override public int getResponseCode() throws IOException {if(beforeResponse!=null)beforeResponse.run();if(failure!=null)throw failure;return status;}
        @Override public long getContentLengthLong(){return length;}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));}
        @Override public InputStream getErrorStream(){return getInputStream();}
        JSONObject body() throws Exception{return new JSONObject(sent.toString("UTF-8"));}
    }
    static final class Harness {
        final TestPreferences memory=new TestPreferences();
        final SessionStore store=new SessionStore(memory.prefs);
        final Deque<Connection> queued=new ArrayDeque<>(); final List<URL> urls=new ArrayList<>();
        final ApiClient client=new ApiClient(store,url->{urls.add(url);Connection c=queued.poll();if(c==null)throw new AssertionError("unexpected HTTP request "+url);return c;});
        Connection raw(String value) throws Exception{Connection c=new Connection(value);queued.add(c);return c;}
        Connection data(Object value) throws Exception{return raw(new JSONObject().put("code",0).put("data",value).toString());}
        void login(){store.save(TestPreferences.session("access","refresh"));}
    }
    static JSONObject tokens(boolean user) throws Exception {
        JSONObject result=new JSONObject().put("access_token","next-access").put("refresh_token","next-refresh").put("expires_in",3600);
        if(user)result.put("user",new JSONObject().put("username","account").put("member_level","pro").put("member_expire_time","m").put("trial_expire_time","t").put("trial_remaining_days",4).put("is_pro",true));
        return result;
    }
    static DeviceItem target(String uuid,String type){return new DeviceItem(42,"target","",type,uuid,"online","","",3389);}

    @Test public void loginPersistsOnlyCompleteResponsesAndHandlesOptionalUser() throws Exception {
        for(boolean user:new boolean[]{false,true}) {
            Harness h=new Harness();Connection c=h.data(tokens(user));
            Session s=h.client.login(" https://unit.invalid/ ","identifier","fake-password");
            assertEquals("next-access",h.store.require().accessToken);assertEquals("https://unit.invalid",s.serverUrl);
            assertEquals(user?"account":"identifier",s.username);assertEquals(user,s.isPro);
            assertEquals(user?4:0,s.trialRemainingDays); assertEquals("POST",c.getRequestMethod());
            assertEquals("identifier",c.body().getString("identifier"));assertEquals("fake-password",c.body().getString("password"));
            assertNull(c.getRequestProperty("Authorization"));assertTrue(c.disconnected);
        }
        for(String missing:new String[]{"access_token","refresh_token"}) {
            Harness h=new Harness();JSONObject data=tokens(false);data.remove(missing);Connection c=h.data(data);
            assertThrows(IOException.class,()->h.client.login("https://unit.invalid","user","fake"));
            assertNull(h.store.load());assertTrue(c.disconnected);
        }
    }

    @Test public void versionPolicyAndHttpErrorsRemainDistinct() throws Exception {
        Harness h=new Harness();h.data(new JSONObject().put("latest_version","1.14.0").put("min_supported_version","1.13.0"));
        assertNotNull(h.client.getClientVersionPolicy("https://unit.invalid"));
        for(String missing:new String[]{"latest_version","min_supported_version"}) {
            JSONObject data=new JSONObject().put("latest_version","1.14").put("min_supported_version","1.13");data.remove(missing);h.data(data);
            assertThrows(IOException.class,()->h.client.getClientVersionPolicy("https://unit.invalid"));
        }
        for(int status:new int[]{200,403}) {
            Connection c=h.raw("{\"code\":7,\"msg\":\"denied\",\"error_code\":\"signal.send.rejected\"}");c.status=status;
            ApiClient.ApiException error=assertThrows(ApiClient.ApiException.class,()->h.client.getClientVersionPolicy("https://unit.invalid"));
            assertEquals(status,error.httpStatus);assertEquals(7,error.code);assertEquals("denied",error.getMessage());assertTrue(c.disconnected);
        }
        Connection invalid=h.raw("<html>bad gateway</html>");invalid.status=502;
        IOException error=assertThrows(IOException.class,()->h.client.getClientVersionPolicy("https://unit.invalid"));
        assertTrue(error.getMessage().contains("502"));assertTrue(invalid.disconnected);
        Connection oversized=h.data(new JSONObject());oversized.length=ApiClient.MAX_RESPONSE_BYTES+1L;
        assertThrows(IOException.class,()->h.client.getClientVersionPolicy("https://unit.invalid"));assertTrue(oversized.disconnected);
        Connection failed=h.raw("");failed.failure=new IOException("simulated I/O");
        assertEquals("simulated I/O",assertThrows(IOException.class,()->h.client.getClientVersionPolicy("https://unit.invalid")).getMessage());
        assertTrue(failed.disconnected);
    }

    @Test public void listDevicesSkipsNonObjectsAndParsesLegacyAndExplicitAccess() throws Exception {
        Harness h=new Harness();h.login();
        JSONObject legacy=new JSONObject().put("device_id",42).put("device_name","legacy").put("service_port",3390);
        JSONObject modern=new JSONObject().put("device_id",43).put("device_type","win11").put("wake_available",true)
                .put("remote_access",new JSONObject().put("protocol","ssh").put("enabled",true).put("port",22));
        Connection c=h.data(new JSONArray().put(legacy).put("invalid item").put(modern));
        List<DeviceItem> devices=h.client.listDevices();assertEquals(2,devices.size());assertEquals("legacy",devices.get(0).displayName());
        assertFalse(devices.get(0).remoteAccessEnabled);assertEquals(3390,devices.get(0).remoteAccessPort);
        assertTrue(devices.get(1).remoteAccessEnabled);assertEquals("ssh",devices.get(1).remoteAccessProtocol);assertEquals(22,devices.get(1).remoteAccessPort);
        assertEquals("Bearer access",c.getRequestProperty("Authorization"));assertTrue(c.disconnected);
        h.data(new JSONObject());assertTrue(h.client.listDevices().isEmpty());
        h.data("primitive data");assertTrue(h.client.listDevices().isEmpty());
    }

    @Test public void openRejectsUnregisteredOrUnsupportedDevicesBeforeHttp() throws Exception {
        Harness h=new Harness();Session s=TestPreferences.session("a","r");
        for(Session invalid:new Session[]{s.withDevice(target("","win10")),s.withDevice(new DeviceItem(0,"","","win10","uuid","","","",0))}) {
            h.store.save(invalid);
            assertEquals(ErrorCodes.RuntimeDeviceNotRegistered,assertThrows(AppException.class,()->h.client.openP2P("job",target("peer","win10"))).errorCode);
        }
        h.login();
        for(DeviceItem invalid:new DeviceItem[]{target("","win10"),target("peer","android")}) {
            assertEquals(ErrorCodes.ConnectionAuthorizeInvalidRequest,assertThrows(AppException.class,()->h.client.openP2P("job",invalid)).errorCode);
        }
        assertTrue(h.urls.isEmpty());
    }

    @Test public void openResolvesRemoteAccessFromAllSupportedResponseShapes() throws Exception {
        for(int shape=0;shape<4;shape++) {
            Harness h=new Harness();h.login();JSONObject data=new JSONObject().put("connection_id","connection").put("log_id",8).put("access_grant","test-grant");
            if(shape>0)data.put("target",new JSONObject().put("service_port",3390));
            if(shape==2)data.getJSONObject("target").put("remote_access",new JSONObject().put("protocol","ssh").put("port",22));
            if(shape==3)data.put("target_remote_access",new JSONObject().put("protocol","vnc").put("port",5900));
            Connection c=h.data(data);ApiClient.OpenResult opened=h.client.openP2P("job-1",target("peer-uuid","win10"));
            assertEquals(new int[]{3389,3390,22,5900}[shape],opened.targetRdpPort);
            assertEquals(new String[]{"rdp","rdp","ssh","vnc"}[shape],opened.targetRemoteProtocol);
            assertEquals("peer-uuid",c.body().getString("target_device_uuid"));assertEquals(7,c.body().getLong("source_device_id"));
            assertEquals("job-1",c.body().getString("client_job_id"));
        }
    }

    @Test public void unauthorizedRefreshesOnceAndRetriesWithRotatedAccessToken() throws Exception {
        for(boolean user:new boolean[]{false,true}) {
            Harness h=new Harness();h.login();Connection denied=h.raw("{\"code\":401}");denied.status=401;
            Connection refresh=h.data(tokens(user));Connection success=h.data(new JSONArray());
            assertTrue(h.client.listDevices().isEmpty());assertEquals(3,h.urls.size());
            assertEquals("refresh",refresh.body().getString("refresh_token"));
            assertEquals("Bearer next-access",success.getRequestProperty("Authorization"));
            assertEquals(user?"account":"user",h.store.require().username);
            assertEquals("private-test",h.store.require().wgPrivateKeyHex);
        }
        Harness h=new Harness();h.login();Connection denied=h.raw("{\"code\":401}");denied.status=401;
        h.data(tokens(false));Connection again=h.raw("{\"code\":401}");again.status=401;
        assertThrows(ApiClient.ApiException.class,h.client::listDevices);assertEquals(3,h.urls.size());
    }

    @Test public void alreadyRefreshedSessionIsReusedAndOtherErrorsDoNotRefresh() throws Exception {
        Harness h=new Harness();h.login();Connection denied=h.raw("{\"code\":401}");denied.status=401;
        denied.beforeResponse=()->h.store.save(TestPreferences.session("concurrent-access","concurrent-refresh"));
        Connection success=h.data(new JSONArray());assertTrue(h.client.listDevices().isEmpty());
        assertEquals(2,h.urls.size());assertEquals("Bearer concurrent-access",success.getRequestProperty("Authorization"));
        Connection forbidden=h.raw("{\"code\":403}");forbidden.status=403;
        assertThrows(ApiClient.ApiException.class,h.client::listDevices);assertEquals(3,h.urls.size());
    }

    @Test public void freshSessionSkipsRefreshAndExpiringSessionValidatesBothTokens() throws Exception {
        Harness h=new Harness();h.login();assertEquals("access",h.client.ensureFreshSession().accessToken);assertTrue(h.urls.isEmpty());
        Session expiring=TestPreferences.session("a","r").withTokens("a","r",0,"user","pro","m","t",2,true);
        for(String missing:new String[]{"access_token","refresh_token"}) {
            h.store.save(expiring);JSONObject data=tokens(false);data.remove(missing);h.data(data);
            assertThrows(IOException.class,h.client::ensureFreshSession);assertEquals("a",h.store.require().accessToken);
        }
        h.data(tokens(false));assertEquals("next-access",h.client.ensureFreshSession().accessToken);
    }

    @Test public void endReportsAndAccountOperationsHaveCorrectBodies() throws Exception {
        Harness h=new Harness();h.login();ApiClient.OpenResult opened=new ApiClient.OpenResult("c",8,"g",3389,"rdp",0,"","");
        for(boolean empty:new boolean[]{false,true}) {
            Connection c=h.data(new JSONObject());
            h.client.reportP2PEnd(opened,target("peer","win10"),false,empty?null:"hard",empty?"":"symm",empty?null:"udp4",empty?null:"udp4",empty?null:"udp4",2,empty?null:ErrorCodes.TraversalPunchTimeout,empty?null:"timeout");
            assertEquals(empty?"unknown":"hard",c.body().getString("source_nat_type"));assertEquals(empty?"unknown":"symm",c.body().getString("target_nat_type"));
            assertEquals(empty?"":"timeout",c.body().getString("error_message"));assertFalse(c.body().has("error_msg"));
        }
        Connection alias=h.data(new JSONObject());h.client.updateDeviceAlias(42,"new-alias");assertEquals("new-alias",alias.body().getString("alias"));
        h.data(new JSONObject());assertEquals("send_failed",h.client.wakeDevice(42));
        h.data(new JSONObject().put("status","sent"));assertEquals("sent",h.client.wakeDevice(42));
        Connection code=h.data(new JSONObject());h.client.sendVerificationCode("https://unit.invalid","fake@example.invalid","register");assertEquals("register",code.body().getString("code_type"));
        for(String invite:new String[]{null,"","invite"}) {
            Connection c=h.data(new JSONObject());h.client.registerByEmail("https://unit.invalid","user","fake@example.invalid","fake","123456",invite);
            assertEquals(invite!=null&&!invite.isEmpty(),c.body().has("invite_code"));
        }
        Connection reset=h.data(new JSONObject());h.client.resetPassword("https://unit.invalid","fake@example.invalid","123456","new-fake");assertEquals("new-fake",reset.body().getString("new_password"));
        assertTrue(ApiClient.buildPunchToken(1,2).matches("1-2-[0-9]+-[a-z]{8}"));
        assertTrue(ApiClient.buildAttemptId(2,3).matches("attempt-2-3-[0-9]+-[a-z]{8}"));
        assertTrue(ApiClient.buildClientJobId(2).matches("job-2-[0-9]+-[a-z]{8}"));
        assertTrue(ApiClient.buildMessageId().matches("msg-[0-9]+-[a-z]{8}"));
    }
}
