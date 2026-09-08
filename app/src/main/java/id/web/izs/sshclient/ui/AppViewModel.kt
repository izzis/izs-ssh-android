package id.web.izs.sshclient.ui

import androidx.lifecycle.ViewModel

/**
 * Survives Activity recreation (rotation): holds the session [AppState] so
 * the RAM-only vault passphrase is kept across rotate — the user is never
 * re-asked for the master password just for turning the phone.
 */
class AppViewModel : ViewModel() {
    var state: AppState? = null
}
