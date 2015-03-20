
package net.java.otr4j;

import net.java.otr4j.session.Session;

public interface OtrDataListener {

    void onTransferComplete(String offerId, Session session, String urlString, String mimeType,
            String fileLocalPath);

    void onTransferFailed(String offerId, Session session, String urlString, String reason);

    void onTransferProgress(String offerId, Session session, String urlString, float f);

    boolean onTransferRequested(String offerId, Session session, String urlString);
}
