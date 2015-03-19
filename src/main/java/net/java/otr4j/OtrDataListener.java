
package net.java.otr4j;

import net.java.otr4j.session.SessionID;

public interface OtrDataListener {

    void onTransferComplete(String offerId, SessionID sessionId, String urlString, String mimeType,
            String fileLocalPath);

    void onTransferFailed(String offerId, SessionID sessionId, String urlString, String reason);

    void onTransferProgress(String offerId, SessionID sessionId, String urlString, float f);

    boolean onTransferRequested(String offerId, SessionID sessionId, String urlString);
}
