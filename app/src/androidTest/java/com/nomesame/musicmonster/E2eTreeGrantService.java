package com.nomesame.musicmonster;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;

/** Test-provider owner issues the real tree grant, just as the system picker would. */
public class E2eTreeGrantService extends Service {
    private final Messenger endpoint = new Messenger(new Handler(Looper.getMainLooper(), request -> {
        if (getPackageManager().checkSignatures(request.sendingUid, Process.myUid())
                != PackageManager.SIGNATURE_MATCH || request.replyTo == null) return true;
        grantUriPermission("com.nomesame.musicmonster", E2eTreeDocumentsProvider.treeUri(),
                E2eTreeDocumentsProvider.TREE_GRANT_FLAGS);
        try { request.replyTo.send(Message.obtain(null, 1)); }
        catch (android.os.RemoteException ignored) { }
        return true;
    }));

    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
}
