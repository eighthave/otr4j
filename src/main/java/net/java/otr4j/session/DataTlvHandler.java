
package net.java.otr4j.session;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import net.java.otr4j.OtrDataListener;
import net.java.otr4j.OtrEngineHost;
import net.java.otr4j.OtrException;
import net.java.otr4j.crypto.OtrCryptoEngine;

import org.apache.http.HttpException;
import org.apache.http.HttpMessage;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestFactory;
import org.apache.http.HttpResponseFactory;
import org.apache.http.MethodNotSupportedException;
import org.apache.http.ProtocolVersion;
import org.apache.http.RequestLine;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.io.AbstractSessionInputBuffer;
import org.apache.http.impl.io.AbstractSessionOutputBuffer;
import org.apache.http.impl.io.HttpRequestParser;
import org.apache.http.impl.io.HttpRequestWriter;
import org.apache.http.impl.io.HttpResponseWriter;
import org.apache.http.io.HttpMessageWriter;
import org.apache.http.io.SessionInputBuffer;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicLineFormatter;
import org.apache.http.message.BasicLineParser;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.message.LineFormatter;
import org.apache.http.message.LineParser;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;

public class DataTlvHandler {

    public static final String URI_SCHEME = "otr-in-band:";

    private static final ProtocolVersion PROTOCOL_VERSION = new ProtocolVersion("HTTP", 1, 1);
    private static final String OFFER = "OFFER";
    private static final String GET = "GET";
    private static final LineParser LINEPARSER = new BasicLineParser(PROTOCOL_VERSION);
    private static final LineFormatter LINEFORMATTER = new BasicLineFormatter();
    private static final HttpParams PARAMS = new BasicHttpParams();
    private static final HttpRequestFactory REQUESTFACTORY = new MyHttpRequestFactory();
    private static final HttpResponseFactory RESPONSEFACTORY = new DefaultHttpResponseFactory();
    private static final byte[] EMPTY_BODY = new byte[0];
    private static final int MAX_CHUNK_LENGTH = 16384;

    private OtrEngineHost engineHost;
    private Session session;
    private OtrDataListener inboundOtrDataListener;
    private OtrDataListener outboundOtrDataListener;
    private final Logger logger;

    Cache<String, SentOffer> offerCache = CacheBuilder.newBuilder().maximumSize(100).build();
    Cache<String, Request> requestCache = CacheBuilder.newBuilder().maximumSize(100).build();

    // Cache<String, Transfer> transferCache =
    // CacheBuilder.newBuilder().maximumSize(100).build();

    /**
     * @param session The chat session that this handler works for.
     */
    public DataTlvHandler(Session session) {
        this.session = session;
        this.engineHost = session.getHost();
        SessionID sessionID = session.getSessionID();
        logger = Logger.getLogger("DataTLV:" + sessionID.getAccountID() + "-->"
                + sessionID.getUserID());
    }

    public void setInboundOtrDataListener(OtrDataListener listener) {
        inboundOtrDataListener = listener;
    }

    public OtrDataListener getInboundOtrDataListener() {
        return inboundOtrDataListener;
    }

    public void setOutboundOtrDataListener(OtrDataListener listener) {
        outboundOtrDataListener = listener;
    }

    public OtrDataListener getOutboundOtrDataListener() {
        return outboundOtrDataListener;
    }

    public String offerData(URI uri, Map<String, String> headers)
            throws IOException {
        logger.fine("offerData: " + uri);
        MemorySessionOutputBuffer outBuf = new MemorySessionOutputBuffer();
        HttpMessageWriter writer = new HttpRequestWriter(outBuf, LINEFORMATTER, PARAMS);
        HttpMessage req = new BasicHttpRequest(OFFER, uri.toString(), PROTOCOL_VERSION);
        String requestId = UUID.randomUUID().toString();
        req.addHeader("Request-Id", requestId);

        try {
            writer.write(req);
            outBuf.flush();
            sendTlv(TLV.DATA_REQUEST, outBuf.getOutput());
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        } catch (HttpException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        } catch (OtrException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        return requestId;
    }

    /**
     * Handle an incoming message that is requesting something from us, either a
     * {@code OFFER} or a {@code GET}.
     */
    public void processRequest(TLV tlv) {
        logger.fine(TLV.DATA_REQUEST + " == " + tlv.getType() + " " + tlv.toString());

        SessionInputBuffer inBuf = new MemorySessionInputBuffer(tlv.getValue());
        HttpRequestParser parser = new HttpRequestParser(inBuf, LINEPARSER, REQUESTFACTORY,
                PARAMS);
        HttpRequest req;

        try {
            logger.fine("parsing");
            req = (HttpRequest) parser.parse();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        } catch (HttpException e) {
            e.printStackTrace();
            return;
        }
        logger.fine("parsed " + req.toString());

        String requestMethod = req.getRequestLine().getMethod();
        String requestId = req.getFirstHeader("Request-Id").getValue();
        String uriString = req.getRequestLine().getUri();

        if (requestMethod.equals("OFFER")) {
            logger.finest("incoming OFFER " + uriString);
            if (!uriString.startsWith(URI_SCHEME)) {
                logger.finest("Unknown url scheme " + uriString);
                sendResponse(400, "Unknown scheme", requestId, EMPTY_BODY);
                return;
            }
            sendResponse(200, "OK", requestId, EMPTY_BODY);
            String type = null;
            if (req.containsHeader("Mime-Type")) {
                type = req.getFirstHeader("Mime-Type").getValue();
            }
            logger.finest("Incoming Mime-Type " + type);

            if (inboundOtrDataListener != null) {
                SessionID sessionId = session.getSessionID();
                inboundOtrDataListener.onTransferRequested(requestId, sessionId, uriString);
            }

        } else if (requestMethod.equals("GET") && uriString.startsWith(URI_SCHEME)) {
            logger.finest("incoming GET " + uriString);
            ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();

            try {
                SentOffer offer = offerCache.getIfPresent(uriString);
                if (offer == null) {
                    sendResponse(400, "No such offer made", requestId, EMPTY_BODY);
                    return;
                }
                // in case we don't see a response to underlying
                // request, but peer still proceeds
                offer.seen();

                if (!req.containsHeader("Range")) {
                    sendResponse(400, "Range must start with bytes=", requestId, EMPTY_BODY);
                    return;
                }
                String rangeHeader = req.getFirstHeader("Range").getValue();
                String[] spec = rangeHeader.split("=");
                if (spec.length != 2 || !spec[0].equals("bytes")) {
                    sendResponse(400, "Range must start with bytes=", requestId, EMPTY_BODY);
                    return;
                }
                String[] startEnd = spec[1].split("-");
                if (startEnd.length != 2) {
                    sendResponse(400, "Range must be START-END", requestId, EMPTY_BODY);
                    return;
                }

                int start = Integer.parseInt(startEnd[0]);
                int end = Integer.parseInt(startEnd[1]);
                if (end - start + 1 > MAX_CHUNK_LENGTH) {
                    sendResponse(400, "Range must be at most " + MAX_CHUNK_LENGTH, requestId,
                            EMPTY_BODY);
                    return;
                }

                if (outboundOtrDataListener != null) {
                    /*
                     * float percent = ((float) end) / ((float)
                     * fileGet.length());
                     * outboundOtrDataListener.onTransferProgress(offer.getId(),
                     * session.getSessionID(), offer.getUri(), percent);
                     */
                    String mimeType = null;
                    if (req.getFirstHeader("Mime-Type") != null)
                        mimeType = req.getFirstHeader("Mime-Type").getValue();
                    outboundOtrDataListener.onTransferComplete(offer.getId(),
                            session.getSessionID(),
                            offer.getUri(), mimeType, offer.getUri());

                }

                byte[] body = byteBuffer.toByteArray();
                logger.finest("Sent sha1 is " + OtrCryptoEngine.sha1Hash(body));
                sendResponse(200, "OK", requestId, body);

                /*
                 * } catch (UnsupportedEncodingException e) { sendResponse(400,
                 * "Unsupported encoding", requestId, EMPTY_BODY); return; }
                 * catch (IOException e) { sendResponse(400, "IOException",
                 * requestId, EMPTY_BODY); return;
                 */
            } catch (NumberFormatException e) {
                sendResponse(400, "Range is not numeric", requestId, EMPTY_BODY);
                return;
            } catch (Exception e) {
                sendResponse(500, "Unknown error", requestId, EMPTY_BODY);
                return;
            }
        } else {
            logger.finest("Unknown method / url " + requestMethod + " " + uriString);
            sendResponse(400, "OK", requestId, EMPTY_BODY);
        }
    }

    public void processResponse(TLV tlv) {
        logger.fine("processResponse: " + tlv.toString());
    }

    /**
     * Send a response to a {@code OFFER} or a {@code GET} request.
     */
    private void sendResponse(int code, String statusString, String requestId, byte[] body) {
        logger.fine("sendResponse " + code + " " + statusString + " " + requestId + ": "
                + session.getSessionID());
        MemorySessionOutputBuffer outBuf = new MemorySessionOutputBuffer();
        HttpMessageWriter writer = new HttpResponseWriter(outBuf, LINEFORMATTER, PARAMS);
        HttpMessage response = new BasicHttpResponse(new BasicStatusLine(PROTOCOL_VERSION, code,
                statusString));
        response.addHeader("Request-Id", requestId);
        try {
            writer.write(response);
            outBuf.write(body);
            outBuf.flush();
            sendTlv(TLV.DATA_RESPONSE, outBuf.getOutput());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (HttpException e) {
            throw new RuntimeException(e);
        } catch (OtrException e) {
            throw new RuntimeException(e);
        }
    }

    private void sendTlv(int type, byte[] value) throws OtrException {
        List<TLV> requestTlvList = new ArrayList<TLV>(1);
        requestTlvList.add(new TLV(type, value));
        String[] msg = session.transformSending("", requestTlvList);
        for (String part : msg) {
            engineHost.injectMessage(session.getSessionID(), part);
        }
    }

    static class MyHttpRequestFactory implements HttpRequestFactory {
        public MyHttpRequestFactory() {
            super();
        }

        public HttpRequest newHttpRequest(final RequestLine requestline)
                throws MethodNotSupportedException {
            if (requestline == null) {
                throw new IllegalArgumentException("Request line may not be null");
            }
            return new BasicHttpRequest(requestline);
        }

        public HttpRequest newHttpRequest(final String method, final String uri)
                throws MethodNotSupportedException {
            return new BasicHttpRequest(method, uri);
        }
    }

    // TODO should this be a subclass of Request?
    static class SentOffer {
        private String mId;
        private String mUri;
        private Request request;

        public SentOffer(String id, String uri, Request request) {
            this.mId = id;
            this.mUri = uri;
            this.request = request;
        }

        public String getUri() {
            return mUri;
        }

        public String getId() {
            return mId;
        }

        public Request getRequest() {
            return request;
        }

        public void seen() {
            request.seen();
        }
    }

    // TODO this should be a subclass of HttpRequest
    static class Request {

        public Request(String method, SessionID sessionId, String url, int start, int end,
                Map<String, String> headers, byte[] body) {
            this.method = method;
            this.url = url;
            this.start = start;
            this.end = end;
            this.sessionId = sessionId;
            this.headers = headers;
            this.body = body;
        }

        public Request(String method, SessionID sessionId, String url, Map<String, String> headers) {
            this(method, sessionId, url, -1, -1, headers, null);
        }

        public String method;
        public String url;
        public int start;
        public int end;
        public byte[] data;
        public boolean seen = false;
        public SessionID sessionId;
        public Map<String, String> headers;
        public byte[] body;

        public boolean isSeen() {
            return seen;
        }

        public void seen() {
            seen = true;
        }
    }

    /**
     * To maintain compatibility with Android's built-in version of Apache HTTP
     * Core, we need to use this deprecated class.
     */
    @SuppressWarnings("deprecation")
    static class MemorySessionInputBuffer extends AbstractSessionInputBuffer {
        public MemorySessionInputBuffer(byte[] value) {
            init(new ByteArrayInputStream(value), 1000, PARAMS);
        }

        public boolean isDataAvailable(int timeout) throws IOException {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * To maintain compatibility with Android's built-in version of Apache HTTP
     * Core, we need to use this deprecated class.
     */
    @SuppressWarnings("deprecation")
    static class MemorySessionOutputBuffer extends AbstractSessionOutputBuffer {
        ByteArrayOutputStream outputStream;

        public MemorySessionOutputBuffer() {
            outputStream = new ByteArrayOutputStream(1000);
            init(outputStream, 1000, PARAMS);
        }

        public byte[] getOutput() {
            return outputStream.toByteArray();
        }
    }
}
