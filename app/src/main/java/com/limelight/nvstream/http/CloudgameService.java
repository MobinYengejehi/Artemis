package com.limelight.nvstream.http;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.utils.DeviceUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Stack;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class CloudgameService {
    private static final int DEFAULT_HTTPS_PORT = 47984;
    public static final int DEFAULT_HTTP_PORT = 47989;
    public static final int DEFAULT_CLOUDGAME_HTTP_PORT = 47986;
    public static final int LONG_CONNECTION_TIMEOUT = 5000;
    public static final int READ_TIMEOUT = 7000;

    private static boolean verbose = BuildConfig.DEBUG;

    public HttpUrl serviceURL;
    public String  serviceToken;

    private OkHttpClient httpClientConnection;

    public CloudgameService(ComputerDetails.AddressTuple address, String jwtToken) {
        this.serviceURL = new HttpUrl.Builder()
                .scheme("http")
                .host(address.address)
                .port(address.port)
                .build();

        httpClientConnection = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
                .connectTimeout(LONG_CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
                .proxy(Proxy.NO_PROXY)
                .build();

        this.serviceToken = jwtToken;
    }

    public boolean IsServiceValid() {
        try {
            GetServerInfo();
        } catch (IOException exception) {
            return false;
        }

        return true;
    }

    public void UpdateComputerDetails(ComputerDetails details) {
        try {
            String serverInfo = GetServerInfo();

            details.name = GetXmlString(serverInfo, "hostname", false);

            if (details.name == null || details.name.isEmpty()) {
                details.name = "UNKNOWN";
            }

            details.uuid = GetXmlString(serverInfo, "uniqueid", true);

            String permStr = GetXmlString(serverInfo, "Permission", false);

            if (permStr != null) {
                try {
                    details.permission = Integer.parseInt(permStr);
                } catch (Exception exception) {
                    details.permission = -1;
                }
            }

            details.httpsPort = GetHttpsPort(serverInfo);
            details.cloudgamePort = GetCloudgameServiceHttpPort(serverInfo);

            details.macAddress = GetXmlString(serverInfo, "mac", false);

            details.localAddress = MakeTuple(GetXmlString(serverInfo, "LocalIP", false), this.serviceURL.port());

            details.externalPort = GetExternalPort(serverInfo);
            details.remoteAddress = MakeTuple(GetXmlString(serverInfo, "ExternalIP", false), details.externalPort);

            if (details.HasCloudgameService()) {
                details.cloudgameAddress = MakeTuple(GetXmlString(serverInfo, "LocalIP", false), details.cloudgamePort);
            }

            details.vDisplaySupported = GetServerSupportsVDisplay(serverInfo);

            if (details.vDisplaySupported) {
                details.vDisplayDriverReady = GetServerVDisplayDriverReady(serverInfo);
            }

            details.serverCommands = GetServerCmds(serverInfo);

            details.pairState = GetPairState(serverInfo);
            details.runningGameId = GetCurrentGame(serverInfo);

            details.nvidiaServer = GetXmlString(serverInfo, "state", true).contains("MJOLNIR");

            details.cloudgameJWTToken = this.serviceToken;

            details.state = ComputerDetails.State.ONLINE;
        } catch (IOException | XmlPullParserException exception) { }
    }

    public InputStream GetBoxArt(NvApp app) throws IOException {
        ResponseBody resp = OpenHttpConnection(this.serviceURL, "appasset", "appid=" + app.getAppId() + "&AssetType=2&AssetIdx=0", null);
        return resp.byteStream();
    }

    public static LinkedList<NvApp> GetAppListByReader(Reader r) throws XmlPullParserException, IOException {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser xpp = factory.newPullParser();

        xpp.setInput(r);

        int eventType = xpp.getEventType();

        LinkedList<NvApp> appList = new LinkedList<NvApp>();
        Stack<String>     currentTag = new Stack<String>();

        boolean rootTerminated = false;

        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
                case (XmlPullParser.START_TAG):
                    if (xpp.getName().equals("root")) {
                        VerifyResponseStatus(xpp);
                    }

                    currentTag.push(xpp.getName());

                    if (xpp.getName().equals("App")) {
                        appList.addLast(new NvApp());
                    }

                    break;
                case (XmlPullParser.END_TAG):
                    currentTag.pop();

                    if (xpp.getName().equals("root")) {
                        rootTerminated = true;
                    }

                    break;
                case (XmlPullParser.TEXT):
                    NvApp app = appList.getLast();

                    if (currentTag.peek().equals("AppTitle")) {
                        app.setAppName(xpp.getText());
                    } else if (currentTag.peek().equals("ID")) {
                        app.setAppId(xpp.getText());
                    } else if (currentTag.peek().equals("IsHdrSupported")) {
                        app.setHdrSupported(xpp.getText().equals("1"));
                    }

                    break;
            }

            eventType = xpp.next();
        }

        if (!rootTerminated) {
            throw new XmlPullParserException("Malformed XML: Root tag was not terminated");
        }

        ListIterator<NvApp> i = appList.listIterator();

        while (i.hasNext()) {
            NvApp app = i.next();

            if (!app.isInitialized()) {
                LimeLog.warning("GFE returned incomplete app: " + app.getAppId() + " " + app.getAppName());

                i.remove();
            }
        }

        return appList;
    }

    public String GetAppListRaw() throws IOException {
        return OpenHttpConnectionToString(this.serviceURL, "applist");
    }

    public String GetServerInfo() throws IOException {
        return OpenHttpConnectionToString(this.serviceURL, "serverinfo");
    }

    public String OpenHttpConnectionToString(HttpUrl baseUrl, String path) throws IOException {
        return OpenHttpConnectionToString(baseUrl, path, null, null);
    }

    public String OpenHttpConnectionToString(HttpUrl baseUrl, String path, String query) throws IOException {
        return OpenHttpConnectionToString(baseUrl, path, query, null);
    }

    public String OpenHttpConnectionToString(HttpUrl baseUrl, String path, String query, RequestBody requestBody) throws IOException {
        try {
            ResponseBody resp = OpenHttpConnection(baseUrl, path, query, requestBody);
            String respString = resp.string();
            resp.close();

            if (verbose && !path.equals("serverinfo")) {
                LimeLog.info(GetCompleteUrl(baseUrl, path, query)+" -> "+respString);
            }

            return respString;
        } catch (IOException e) {
            if (verbose && !path.equals("serverinfo")) {
                LimeLog.warning(GetCompleteUrl(baseUrl, path, query)+" -> "+e.getMessage());
                e.printStackTrace();
            }

            throw e;
        }
    }

    public ResponseBody OpenHttpConnection(HttpUrl baseUrl, String path, String query, RequestBody requestBody) throws IOException {
        HttpUrl completeUrl = GetCompleteUrl(baseUrl, path, query);

        Request.Builder requestBuilder = new Request.Builder().url(completeUrl);

        if (!this.serviceToken.isEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer " + this.serviceToken);
        }

        Request request;

        if (requestBody == null) {
            request = requestBuilder.get().build();
        } else {
            request = requestBuilder.post(requestBody).build();
        }

        Response response = PerformAndroidTlsHack(httpClientConnection).newCall(request).execute();

        ResponseBody body = response.body();

        if (response.isSuccessful()) {
            return body;
        }

        if (body != null) {
            body.close();
        }

        if (response.code() == 404) {
            throw new FileNotFoundException(completeUrl.toString());
        } else {
            throw new HostHttpResponseException(response.code(), response.message());
        }
    }

    private OkHttpClient PerformAndroidTlsHack(OkHttpClient client) {
        return client.newBuilder().build();
    }

    public HttpUrl GetCompleteUrl(HttpUrl baseUrl, String path, String query) {
        return baseUrl.newBuilder()
                .addPathSegments(path)
                .query(query)
                .addQueryParameter("devicename", DeviceUtils.getModel())
                .addQueryParameter("uniqueid", UUID.randomUUID().toString())
                .addQueryParameter("uuid", UUID.randomUUID().toString())
                .build();
    }

    public int GetCurrentGame(String serverInfo) throws IOException, XmlPullParserException {
        if (GetXmlString(serverInfo, "state", true).endsWith("_SERVER_BUSY")) {
            return Integer.parseInt(GetXmlString(serverInfo, "currentgame", true));
        } else {
            return 0;
        }
    }

    public int GetHttpsPort(String serverInfo) {
        try {
            return Integer.parseInt(GetXmlString(serverInfo, "HttpsPort", true));
        } catch (XmlPullParserException e) {
            e.printStackTrace();
            return DEFAULT_HTTPS_PORT;
        } catch (IOException e) {
            e.printStackTrace();
            return DEFAULT_HTTPS_PORT;
        }
    }

    public int GetCloudgameServiceHttpPort(String serverInfo) {
        try {
            return Integer.parseInt(GetXmlString(serverInfo, "CloudgamePort", true));
        } catch (XmlPullParserException e) {
            e.printStackTrace();
            return 0;
        } catch (IOException e) {
            e.printStackTrace();
            return 0;
        }
    }

    static String GetXmlString(Reader r, String tagname, boolean throwIfMissing) throws XmlPullParserException, IOException {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser xpp = factory.newPullParser();

        xpp.setInput(r);

        int eventType = xpp.getEventType();
        Stack<String> currentTag = new Stack<String>();

        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
                case (XmlPullParser.START_TAG):
                    if (xpp.getName().equals("root")) {
                        VerifyResponseStatus(xpp);
                    }

                    currentTag.push(xpp.getName());

                    break;
                case (XmlPullParser.END_TAG):
                    currentTag.pop();
                    break;
                case (XmlPullParser.TEXT):
                    if (currentTag.peek().equals(tagname)) {
                        return xpp.getText();
                    }

                    break;
            }
            eventType = xpp.next();
        }

        if (throwIfMissing) {
            throw new XmlPullParserException("Missing mandatory field in host response: "+tagname);
        }

        return null;
    }

    static String GetXmlString(String str, String tagname, boolean throwIfMissing) throws XmlPullParserException, IOException {
        return GetXmlString(new StringReader(str), tagname, throwIfMissing);
    }

    static List<String> GetXmlArray(Reader r, String tagname, boolean throwIfMissing) throws XmlPullParserException, IOException {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser xpp = factory.newPullParser();

        xpp.setInput(r);

        int eventType = xpp.getEventType();
        Stack<String> currentTag = new Stack<>();

        List<String> array = new ArrayList<>();

        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
                case (XmlPullParser.START_TAG):
                    currentTag.push(xpp.getName());
                    break;
                case (XmlPullParser.END_TAG):
                    currentTag.pop();
                    break;
                case (XmlPullParser.TEXT):
                    if (currentTag.peek().equals(tagname)) {
                        array.add(xpp.getText());
                    }

                    break;
            }
            eventType = xpp.next();
        }

        if (throwIfMissing && array.isEmpty()) {
            throw new XmlPullParserException("Missing mandatory field in host response: "+tagname);
        }

        return array;
    }

    static List<String> GetXmlArray(String str, String tagname, boolean throwIfMissing) throws XmlPullParserException, IOException {
        return GetXmlArray(new StringReader(str), tagname, throwIfMissing);
    }

    public int GetExternalPort(String serverInfo) {
        try {
            return Integer.parseInt(GetXmlString(serverInfo, "ExternalPort", true));
        } catch (XmlPullParserException e) {
            return serviceURL.port();
        } catch (IOException e) {
            e.printStackTrace();
            return serviceURL.port();
        }
    }

    public boolean GetServerSupportsVDisplay(String serverInfo) throws XmlPullParserException, IOException {
        String supportVdisplay = GetXmlString(serverInfo, "VirtualDisplayCapable", false);

        if (supportVdisplay == null) {
            return false;
        }

        return supportVdisplay.equals("true");
    }

    public boolean GetServerVDisplayDriverReady(String serverInfo) throws XmlPullParserException, IOException {
        String driverReady = GetXmlString(serverInfo, "VirtualDisplayDriverReady", false);

        if (driverReady == null) {
            return false;
        }

        return driverReady.equals("true");
    }

    public List<String> GetServerCmds(String serverInfo) throws XmlPullParserException, IOException {
        return GetXmlArray(serverInfo, "ServerCommand", false);
    }

    public PairingManager.PairState GetPairState() throws IOException, XmlPullParserException {
        return GetPairState(GetServerInfo());
    }

    public PairingManager.PairState GetPairState(String serverInfo) throws IOException, XmlPullParserException {
        return GetXmlString(serverInfo, "PairStatus", true).equals("1") ?
                PairingManager.PairState.PAIRED : PairingManager.PairState.NOT_PAIRED;
    }

    private static void VerifyResponseStatus(XmlPullParser xpp) throws HostHttpResponseException {
        int statusCode = (int)Long.parseLong(xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_code"));

        if (statusCode != 200) {
            String statusMsg = xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_message");

            if (statusCode == -1 && "Invalid".equals(statusMsg)) {
                statusCode = 418;
                statusMsg = "Missing audio capture device. Reinstall GeForce Experience.";
            }

            throw new HostHttpResponseException(statusCode, statusMsg);
        }
    }

    private static ComputerDetails.AddressTuple MakeTuple(String address, int port) {
        if (address == null) {
            return null;
        }

        return new ComputerDetails.AddressTuple(address, port);
    }
}
