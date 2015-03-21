
package net.java.otr4j.session;

import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.logging.Logger;

import net.java.otr4j.OtrDataListener;
import net.java.otr4j.test.dummyclient.DummyClient;

import org.junit.Test;

public class DataTlvHandlerTest {

    private Logger logger = Logger.getLogger("DataTlvHandlerTest");

    @Test
    public void testSendFile() throws Exception {
        DummyClient[] convo = DummyClient.getConversation();
        final DummyClient alice = convo[0];
        final DummyClient bob = convo[1];

        OtrDataListener bobInboundOtrDataListener = new OtrDataListener() {

            public boolean onTransferRequested(String offerId, Session session, String uriString) {
                logger.finer("inbound: " + offerId + " "
                        + session.getSessionID() + " " + uriString);
                session.acceptTransfer(uriString);
                return true;
            }

            public void onTransferProgress(String offerId, Session session, String uriString,
                    float f) {
                logger.finer("inbound: " + offerId + " "
                        + session.getSessionID() + " " + uriString + " " + f);
            }

            public void onTransferFailed(String offerId, Session session, String uriString,
                    String reason) {
                logger.severe("inbound: " + offerId + " " + session.getSessionID()
                        + " " + uriString + " " + reason);
            }

            public void onTransferComplete(String offerId, Session session, String uriString,
                    String mimeType, String fileLocalPath) {
                logger.finer("inbound: " + offerId + " "
                        + session.getSessionID() + " " + uriString + " " + mimeType + " "
                        + fileLocalPath);
            }
        };
        OtrDataListener aliceOutboundOtrDataListener = new OtrDataListener() {

            public boolean onTransferRequested(String offerId, Session session, String uriString) {
                logger.finer("outbound: " + offerId + " "
                        + session.getSessionID() + " " + uriString);
                return false;
            }

            public void onTransferProgress(String offerId, Session session, String uriString,
                    float f) {
                logger.finer("outbound: " + offerId + " "
                        + session.getSessionID() + " " + uriString + " " + f);
            }

            public void onTransferFailed(String offerId, Session session, String uriString,
                    String reason) {
                logger.severe("outbound: " + offerId + " "
                        + session.getSessionID() + " " + uriString + " " + reason);
            }

            public void onTransferComplete(String offerId, Session session, String uriString,
                    String mimeType, String fileLocalPath) {
                logger.finer("outbound: " + offerId + " "
                        + session.getSessionID() + " " + uriString + " " + mimeType + " "
                        + fileLocalPath);
            }
        };

        DummyClient.forceStartOtr(alice, bob);
        assertEquals("The session is not encrypted.", SessionStatus.ENCRYPTED,
                bob.getSession().getSessionStatus());
        assertEquals("The session is not encrypted.", SessionStatus.ENCRYPTED,
                alice.getSession().getSessionStatus());

        File f = File.createTempFile("testSendFile", ".txt");
        FileOutputStream out = new FileOutputStream(f);
        out.write("this is just a test, isn't it?".getBytes());
        out.close();
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
        logger.finer("Sending (" + f.length() + "): -=<" + br.readLine() + ">=-");
        br.close();

        Session aliceSession = alice.getSession();
        Session bobSession = bob.getSession();

        bobSession.setInboundOtrDataListener(bobInboundOtrDataListener);
        aliceSession.setOutboundOtrDataListener(aliceOutboundOtrDataListener);

        aliceSession.offerData(f.getParentFile(), f, "text/plain");

        Thread.sleep(3000);
        bob.exit();
        alice.exit();
    }
}
