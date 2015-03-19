
package net.java.otr4j.session;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.util.logging.Logger;

import net.java.otr4j.OtrDataListener;
import net.java.otr4j.test.dummyclient.DummyClient;

import org.junit.Test;

public class DataTlvHandlerTest {

    private Logger logger = Logger.getLogger("DataTlvHandlerTest");

    @Test
    public void testSendFile() throws Exception {
        DummyClient[] convo = DummyClient.getConversation();
        DummyClient alice = convo[0];
        DummyClient bob = convo[1];

        DummyClient.forceStartOtr(alice, bob);
        assertEquals("The session is not encrypted.", SessionStatus.ENCRYPTED,
                bob.getSession().getSessionStatus());
        assertEquals("The session is not encrypted.", SessionStatus.ENCRYPTED,
                alice.getSession().getSessionStatus());

        File f = File.createTempFile("testSendFile", ".txt");
        FileOutputStream out = new FileOutputStream(f);
        out.write("this is just a test".getBytes());
        out.close();

        Session session = alice.getSession();
        session.setInboundOtrDataListener(inboundOtrDataListener);
        session.setOutboundOtrDataListener(outboundOtrDataListener);
        URI uri = new URI(DataTlvHandler.URI_SCHEME + f.getAbsolutePath());
        session.offerData(uri, null);
        logger.finer("after session.offerData()");

        bob.exit();
        alice.exit();
    }

    private OtrDataListener inboundOtrDataListener = new OtrDataListener() {

        public boolean onTransferRequested(String offerId, SessionID sessionId, String urlString) {
            logger.finer("inbound onTransferRequested: " + offerId + " " + sessionId + " "
                    + urlString);
            return false;
        }

        public void onTransferProgress(String offerId, SessionID sessionId, String urlString,
                float f) {
            logger.finer("inbound onTransferProgress: " + offerId + " " + sessionId + " "
                    + urlString + " " + f);
        }

        public void onTransferFailed(String offerId, SessionID sessionId, String urlString,
                String reason) {
            logger.finest("inbound onTransferFailed: " + offerId + " " + sessionId + " "
                    + urlString + " " + reason);
        }

        public void onTransferComplete(String offerId, SessionID sessionId, String urlString,
                String mimeType, String fileLocalPath) {
            logger.finest("inbound onTransferComplete: " + offerId + " " + sessionId + " "
                    + urlString
                    + " " + mimeType + " " + fileLocalPath);
        }
    };

    private OtrDataListener outboundOtrDataListener = new OtrDataListener() {

        public boolean onTransferRequested(String offerId, SessionID sessionId, String urlString) {
            logger.finest("outbound onTransferRequested: " + offerId + " " + sessionId + " "
                    + urlString);
            return false;
        }

        public void onTransferProgress(String offerId, SessionID sessionId, String urlString,
                float f) {
            logger.finest("outbound onTransferProgress: " + offerId + " " + sessionId + " "
                    + urlString + " " + f);
        }

        public void onTransferFailed(String offerId, SessionID sessionId, String urlString,
                String reason) {
            logger.finest("outbound onTransferFailed: " + offerId + " " + sessionId + " "
                    + urlString + " " + reason);
        }

        public void onTransferComplete(String offerId, SessionID sessionId, String urlString,
                String mimeType, String fileLocalPath) {
            logger.finest("outbound onTransferComplete: " + offerId + " " + sessionId + " "
                    + urlString
                    + " " + mimeType + " " + fileLocalPath);
        }
    };
}
