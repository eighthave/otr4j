
package net.java.otr4j.session;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseFactory;
import org.apache.http.MethodNotSupportedException;
import org.apache.http.ProtocolVersion;
import org.apache.http.RequestLine;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.io.AbstractSessionInputBuffer;
import org.apache.http.impl.io.AbstractSessionOutputBuffer;
import org.apache.http.impl.io.HttpRequestParser;
import org.apache.http.impl.io.HttpRequestWriter;
import org.apache.http.impl.io.HttpResponseParser;
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
    private static final HttpResponseFactory RESPONSEFACTORY = new DefaultHttpResponseFactory();
    private static final byte[] EMPTY_BODY = new byte[0];
    private static final int MAX_CHUNK_LENGTH = 16384;
    private static final int MAX_OUTSTANDING = 5;
    private static final int MAX_TRANSFER_LENGTH = 1024 * 1024 * 64;

    private final OtrEngineHost engineHost;
    private final Session session;
    private final HttpRequestFactory requestFactory = new MyHttpRequestFactory();
    private OtrDataListener inboundOtrDataListener;
    private OtrDataListener outboundOtrDataListener;
    private final Logger logger;

    Cache<String, OfferRequest> offerCache = CacheBuilder.newBuilder().maximumSize(100).build();
    Cache<String, OtrDataRequest> requestCache = CacheBuilder.newBuilder().maximumSize(100).build();
    Cache<String, InboundTransfer> transferCache = CacheBuilder.newBuilder().maximumSize(100).build();

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

    public String offerData(File fileRoot, File f, Map<String, String> headers)
            throws IOException {
        URI uri = fileRoot.toURI().relativize(f.toURI());
        String uriString = DataTlvHandler.URI_SCHEME + uri.getPath();
        logger.fine("offerData: " + uriString);
        OfferRequest req = new OfferRequest(uriString, f);
        if (headers != null)
            for (String key : headers.keySet())
                req.setHeader(key, headers.get(key));
        long length = f.length();
        if (length > MAX_TRANSFER_LENGTH) {
            throw new IOException("Length too large: " + length);
        }
        req.setHeader("File-Length", String.valueOf(length));

        offerCache.put(uriString, req);
        return sendRequest(req);
    }

    // TODO this needs to be exposed or handled somewhere
    public void retryRequests() {
        // Resend all unfilled requests
        for (OtrDataRequest request : requestCache.asMap().values()) {
            if (!request.isSeen())
                sendRequest(request);
        }
    }

    private GetRequest performGetData(String url, Map<String, String> headers, int start, int end) {
        GetRequest request = new GetRequest(url, start, end);
        sendRequest(request);
        return request;
    }

    private String sendRequest(OtrDataRequest req) {
        logger.finer(req.getUriString());
        MemorySessionOutputBuffer outBuf = new MemorySessionOutputBuffer();
        HttpMessageWriter writer = new HttpRequestWriter(outBuf, LINEFORMATTER, PARAMS);

        try {
            writer.write(req);
            outBuf.flush();
            sendTlv(TLV.DATA_REQUEST, outBuf.getOutput());
            requestCache.put(req.requestId, req);
            return req.requestId;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (HttpException e) {
            throw new RuntimeException(e);
        } catch (OtrException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean acceptTransfer(String uriString) {
        InboundTransfer transfer = transferCache.getIfPresent(uriString);
        logger.fine(uriString + " " + transfer + " " + transferCache.size());
        for (Map.Entry<String, InboundTransfer> m : transferCache.asMap().entrySet())
            logger.fine("transferCache: " + m.getValue().toString());
        if (transfer != null) {
            return transfer.perform();
        }
        return false;
    }

    /**
     * Handle an incoming message that is requesting something from us, either a
     * {@code OFFER} or a {@code GET}.
     */
    public void processRequest(TLV tlv) {
        logger.fine(new String(tlv.getValue()));

        SessionInputBuffer inBuf = new MemorySessionInputBuffer(tlv.getValue());
        HttpRequestParser parser = new HttpRequestParser(inBuf, LINEPARSER, requestFactory,
                PARAMS);
        HttpRequest req;

        try {
            req = (HttpRequest) parser.parse();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        } catch (HttpException e) {
            e.printStackTrace();
            return;
        }
        logger.fine(req.toString());

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
            if (!req.containsHeader("File-Length")) {
                sendResponse(400, "File-Length must be supplied", requestId, EMPTY_BODY);
                return;
            }
            int length = Integer.parseInt(req.getFirstHeader("File-Length").getValue());
            String type = null;
            if (req.containsHeader("Mime-Type")) {
                type = req.getFirstHeader("Mime-Type").getValue();
            }
            logger.finest("Incoming Mime-Type " + type);

            InboundTransfer transfer = new InboundTransfer(uriString, type, length);
            logger.finer("transferCache.put(" + transfer.url);
            transferCache.put(uriString, transfer);

            if (inboundOtrDataListener != null)
                inboundOtrDataListener.onTransferRequested(requestId, session,
                        uriString);
            if (outboundOtrDataListener != null)
                outboundOtrDataListener.onTransferRequested(requestId, session,
                        uriString);
        } else if (requestMethod.equals("GET") && uriString.startsWith(URI_SCHEME)) {
            logger.finest("incoming GET " + uriString);
            ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();

            try {
                OfferRequest offer = offerCache.getIfPresent(uriString);
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

                FileInputStream is = new FileInputStream(offer.file);
                readIntoByteBuffer(byteBuffer, is, start, end);
                is.close();

                float percent = ((float) end) / ((float) offer.file.length());
                outboundOtrDataListener.onTransferProgress(offer.requestId,
                        session, offer.getUriString(), percent);

                String mimeType = null;
                if (req.getFirstHeader("Mime-Type") != null)
                    mimeType = req.getFirstHeader("Mime-Type").getValue();
                if (inboundOtrDataListener != null) {
                    inboundOtrDataListener.onTransferComplete(offer.requestId,
                            session, offer.getUriString(), mimeType, offer.getUriString());
                }
                if (outboundOtrDataListener != null) {
                    outboundOtrDataListener.onTransferComplete(offer.requestId,
                            session, offer.getUriString(), mimeType, offer.getUriString());
                }

                byte[] body = byteBuffer.toByteArray();
                logger.finest("Sent sha1 is " + OtrCryptoEngine.sha1Hash(body));
                sendResponse(200, "OK", requestId, body);

            } catch (UnsupportedEncodingException e) {
                sendResponse(400, "Unsupported encoding", requestId, EMPTY_BODY);
                return;
            } catch (IOException e) {
                sendResponse(400, "IOException", requestId, EMPTY_BODY);
                return;
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
        logger.fine(new String(tlv.getValue()));
        SessionInputBuffer buffer = new MemorySessionInputBuffer(tlv.getValue());
        HttpResponseParser parser = new HttpResponseParser(buffer, LINEPARSER, RESPONSEFACTORY,
                PARAMS);
        HttpResponse res;
        try {
            res = (HttpResponse) parser.parse();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (HttpException e) {
            e.printStackTrace();
            return;
        }
        logger.fine(res.toString());

        String requestId = res.getFirstHeader("Request-Id").getValue();
        OtrDataRequest request = requestCache.getIfPresent(requestId);
        if (request == null) {
            logger.finer("Unknown request ID " + requestId);
            return;
        }

        if (request.isSeen()) {
            logger.finer("Already seen request ID " + requestId);
            return;
        }

        request.seen();
        int statusCode = res.getStatusLine().getStatusCode();
        if (statusCode != 200) {
            logger.finer("got status " + statusCode + ": " + res.getStatusLine().getReasonPhrase());
            // TODO handle error
            return;
        }

        // TODO handle success
        try {
            ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
            readIntoByteBuffer(byteBuffer, buffer);
            if (request instanceof GetRequest) {
                GetRequest getRequest = (GetRequest) request;
                InboundTransfer transfer = transferCache.getIfPresent(request.getUriString());
                if (transfer == null) {
                    logger.severe("Transfer expired for url " + request.getUriString());
                    return;
                }
                transfer.chunkReceived(getRequest, byteBuffer.toByteArray());
                if (transfer.isDone()) {
                    logger.fine("Transfer complete for " + getRequest.getUriString() + " "
                            + inboundOtrDataListener + " " + outboundOtrDataListener);
                    logger.finer("Received: (" + transfer.getBuffer().length + ") "
                            + new String(transfer.getBuffer()));
                    if (inboundOtrDataListener != null)
                        inboundOtrDataListener.onTransferComplete(
                                null,
                                session,
                                transfer.url,
                                transfer.type,
                                "PLACEHOLDER");
                    if (outboundOtrDataListener != null)
                        outboundOtrDataListener.onTransferComplete(
                                null,
                                session,
                                transfer.url,
                                transfer.type,
                                "PLACEHOLDER");
                } else {
                    if (inboundOtrDataListener != null)
                        inboundOtrDataListener.onTransferProgress(null, session,
                                transfer.url,
                                ((float) transfer.chunksReceived) / transfer.chunks);
                    if (outboundOtrDataListener != null)
                        outboundOtrDataListener.onTransferProgress(null, session,
                                transfer.url,
                                ((float) transfer.chunksReceived) / transfer.chunks);
                    transfer.perform();
                    logger.finer("Progress " + transfer.chunksReceived + " / " + transfer.chunks);
                }
            }
        } catch (IOException e) {
            logger.finer("Could not read line from response");
        }
    }

    /**
     * Send a response to a {@code OFFER} or a {@code GET} request.
     */
    private void sendResponse(int code, String statusString, String requestId, byte[] body) {
        logger.fine(code + " " + statusString + " " + requestId + ": "
                + session.getSessionID() + "size: " + body.length);
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

    private static void readIntoByteBuffer(ByteArrayOutputStream byteBuffer, FileInputStream is,
            int start, int end)
            throws IOException {
        if (start != is.skip(start)) {
            return;
        }
        int size = end - start + 1;
        int buffersize = 1024;
        byte[] buffer = new byte[buffersize];

        int len = 0;
        while ((len = is.read(buffer)) != -1) {
            if (len > size) {
                len = size;
            }
            byteBuffer.write(buffer, 0, len);
            size -= len;
        }
    }

    private static void readIntoByteBuffer(ByteArrayOutputStream byteBuffer, SessionInputBuffer sib)
            throws IOException {
        int buffersize = 1024;
        byte[] buffer = new byte[buffersize];

        int len = 0;
        while ((len = sib.read(buffer)) != -1) {
            byteBuffer.write(buffer, 0, len);
        }
    }

    class MyHttpRequestFactory implements HttpRequestFactory {
        public MyHttpRequestFactory() {
            super();
        }

        public HttpRequest newHttpRequest(final RequestLine requestline)
                throws MethodNotSupportedException {
            if (requestline == null) {
                throw new IllegalArgumentException("Request line may not be null");
            }
            return newHttpRequest(requestline.getMethod(), requestline.getUri());
        }

        public HttpRequest newHttpRequest(final String method, final String uri)
                throws MethodNotSupportedException {
            if (method.equals(GET))
                return new GetRequest(uri);
            else if (method.equals(OFFER))
                return new OfferRequest(uri);
            else
                throw new MethodNotSupportedException(method);
        }
    }

    static class OfferRequest extends OtrDataRequest {

        final File file;

        /**
         * The received {@code OFFER} is parsed into this
         */
        public OfferRequest(String uri) {
            super(OFFER, uri);
            this.file = null;
        }

        /**
         * The sent {@code OFFER} is created like this
         */
        public OfferRequest(String uri, File f) {
            super(OFFER, uri);
            this.file = f;
        }
    }

    static class GetRequest extends OtrDataRequest {
        public GetRequest(String uri, int start, int end) {
            super(GET, uri);
            this.start = start;
            this.end = end;
            String rangeSpec = "bytes=" + start + "-" + end;
            setHeader("Range", rangeSpec);
        }

        public GetRequest(String uri) {
            super(GET, uri);
            this.start = -1;
            this.end = -1;
        }

        public final int start;
        public final int end;
    }

    static abstract class OtrDataRequest extends BasicHttpRequest {

        public OtrDataRequest(String method, String uri) {
            super(method, uri, PROTOCOL_VERSION);
            this.requestId = UUID.randomUUID().toString();
            setHeader("Request-Id", requestId);
        }

        public final String requestId;
        public boolean seen = false;

        public String getUriString() {
            return getRequestLine().getUri();
        }

        public boolean isSeen() {
            return seen;
        }

        public void seen() {
            seen = true;
        }
    }

    public class InboundTransfer {
        public String url;
        public String type;
        public int chunks = 0;
        public int chunksReceived = 0;
        private byte[] buffer;
        private int length = 0;
        private int current = 0;
        protected Set<GetRequest> outstanding;

        public InboundTransfer(String url, String type, int length) {
            this.url = url;
            this.type = type;
            this.length = length;
            if (length > DataTlvHandler.MAX_TRANSFER_LENGTH || length <= 0) {
                throw new RuntimeException("Invalid transfer size " + length);
            }
            chunks = ((length - 1) / DataTlvHandler.MAX_CHUNK_LENGTH) + 1;
            buffer = new byte[length];
            outstanding = new HashSet<GetRequest>();
        }

        public boolean perform() {
            // TODO global throttle rather than this local hack
            logger.finer(outstanding.size() + " / " + length);
            while (outstanding.size() < DataTlvHandler.MAX_OUTSTANDING) {
                if (current >= length)
                    return false;
                int end = current + DataTlvHandler.MAX_CHUNK_LENGTH - 1;
                if (end >= length) {
                    end = length - 1;
                }
                Map<String, String> headers = new HashMap<String, String>();
                GetRequest request = performGetData(url, headers, current, end);
                outstanding.add(request);
                current = end + 1;
            }
            return true;
        }

        public boolean isDone() {
            logger.finer(chunksReceived + " / " + chunks);
            return chunksReceived == chunks;
        }

        public void chunkReceived(GetRequest request, byte[] bs) {
            logger.finer(request.getUriString() + " (" + bs.length + ") " + new String(bs));
            chunksReceived++;
            System.arraycopy(bs, 0, buffer, request.start, bs.length);
            outstanding.remove(request);
        }

        byte[] getBuffer() {
            return buffer;
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
