
package net.java.otr4j.session;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileOutputStream;
import java.util.logging.Logger;

import net.java.otr4j.OtrDataListener;
import net.java.otr4j.test.dummyclient.DummyClient;

import org.junit.Test;

public class DataTlvHandlerTest {

    private Logger logger = Logger.getLogger("DataTlvHandlerTest");

    private OtrDataListener bobInboundOtrDataListener = new OtrDataListener() {

        public boolean onTransferRequested(String offerId, Session session, String uriString) {
            logger.finer("inbound onTransferRequested: " + offerId + " "
                    + session.getSessionID() + " " + uriString);
            session.acceptTransfer(uriString);
            return true;
        }

        public void onTransferProgress(String offerId, Session session, String uriString,
                float f) {
            logger.finer("inbound onTransferProgress: " + offerId + " "
                    + session.getSessionID() + " " + uriString + " " + f);
        }

        public void onTransferFailed(String offerId, Session session, String uriString,
                String reason) {
            logger.finest("inbound onTransferFailed: " + offerId + " " + session.getSessionID()
                    + " " + uriString + " " + reason);
        }

        public void onTransferComplete(String offerId, Session session, String uriString,
                String mimeType, String fileLocalPath) {
            logger.finest("inbound onTransferComplete: " + offerId + " "
                    + session.getSessionID() + " " + uriString + " " + mimeType + " "
                    + fileLocalPath);
        }
    };
    private OtrDataListener aliceOutboundOtrDataListener = new OtrDataListener() {

        public boolean onTransferRequested(String offerId, Session session, String uriString) {
            logger.finest("outbound onTransferRequested: " + offerId + " "
                    + session.getSessionID() + " " + uriString);
            return false;
        }

        public void onTransferProgress(String offerId, Session session, String uriString,
                float f) {
            logger.finest("outbound onTransferProgress: " + offerId + " "
                    + session.getSessionID() + " " + uriString + " " + f);
        }

        public void onTransferFailed(String offerId, Session session, String uriString,
                String reason) {
            logger.finest("outbound onTransferFailed: " + offerId + " "
                    + session.getSessionID() + " " + uriString + " " + reason);
        }

        public void onTransferComplete(String offerId, Session session, String uriString,
                String mimeType, String fileLocalPath) {
            logger.finest("outbound onTransferComplete: " + offerId + " "
                    + session.getSessionID() + " " + uriString + " " + mimeType + " "
                    + fileLocalPath);
        }
    };

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

        Session aliceSession = alice.getSession();
        Session bobSession = bob.getSession();

        bobSession.setInboundOtrDataListener(bobInboundOtrDataListener);
        aliceSession.setOutboundOtrDataListener(aliceOutboundOtrDataListener);

        aliceSession.offerData(f.getAbsolutePath(), null);

        bob.exit();
        alice.exit();
    }
}
