package com.phlox.simpleserver.security

import androidx.compose.runtime.mutableStateListOf
import com.phlox.server.utils.SHTTPSLoggerProxy
import com.phlox.simpleserver.AppConfig
import com.phlox.simpleserver.SHTTPSApp
import com.phlox.simpleserver.SHTTPSConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime side of the "White list of clients (IPs)" setting - the desktop counterpart of what
 * `ServerService` does on Android.
 *
 * The server itself already does the filtering: [SHTTPSApp] hands the allowed addresses to
 * `SimpleHttpServer.allowedClientAddresses` when it starts, and the accept loop drops anything
 * else before a single byte of HTTP is read. What is missing on desktop is the other half of
 * `WhiteListMode.ASK_AT_RUNTIME`: turning such a rejection into a question for the user and
 * applying their answer to the already running server.
 *
 * Lives above the navigation graph (created in `AppContent`) rather than inside [com.phlox.simpleserver.screens.home.HomeViewModel],
 * because the prompt windows must be able to appear while the main window sits hidden in the tray.
 */
class ClientApprovalController(private val config: AppConfig) {
    private val logger = SHTTPSLoggerProxy.getLogger("ClientApprovalController")

    /**
     * IPs a prompt is already open (or queued) for, and IPs the user said no to. Both are read
     * and written from the server's accept loop thread as well as from the UI thread, hence the
     * concurrent sets. Same semantics as Android: a denied IP is never asked about again for the
     * lifetime of the server, and a pending question never expires by itself.
     */
    private val ipsPending = ConcurrentHashMap.newKeySet<String>()
    private val ipsDenied = ConcurrentHashMap.newKeySet<String>()

    /**
     * What the prompt windows render. Snapshot state, so it is only ever touched from the main
     * dispatcher - the accept loop reaches it through [incoming].
     */
    private val _prompts = mutableStateListOf<String>()
    val prompts: List<String> get() = _prompts

    //a retrying browser or a port scan hits onClientRejected many times per second; tryEmit into
    //a bounded buffer means such a burst is dropped instead of blocking the accept loop
    private val incoming = MutableSharedFlow<String>(extraBufferCapacity = 64)

    private var shttpsApp: SHTTPSApp? = null
    private var collectJob: Job? = null

    /**
     * Invoked on the UI thread right after [allowAndSave] has written a new whitelist into the
     * config. The settings screen keeps its own copy of these two values, and a prompt answered
     * from outside it would otherwise leave that copy stale - showing an out of date list in the
     * editor, and silently dropping the just added address (and the PREDEFINED mode bit) the next
     * time the user saves it.
     */
    var onWhiteListPersisted: (() -> Unit)? = null

    fun attach(app: SHTTPSApp, scope: CoroutineScope) {
        shttpsApp = app
        collectJob?.cancel()
        collectJob = scope.launch(Dispatchers.Main) {
            incoming.collect { ip ->
                if (!_prompts.contains(ip)) {
                    _prompts.add(ip)
                }
            }
        }
    }

    fun detach() {
        collectJob?.cancel()
        collectJob = null
        shttpsApp = null
        onWhiteListPersisted = null
        onServerStopped()
    }

    /**
     * Called from the server's accept loop thread for every connection dropped by the whitelist.
     * Deduplicates here, before anything is handed to the UI, so that a single unknown client
     * retrying in a loop produces exactly one prompt.
     */
    fun onClientRejected(ip: String) {
        if (ipsDenied.contains(ip)) return
        if (!ipsPending.add(ip)) return
        if (!incoming.tryEmit(ip)) {
            //buffer overflow: drop this prompt, but do not leave the IP marked as pending or it
            //would never be asked about again
            ipsPending.remove(ip)
            logger.w("Dropped a new-client prompt for $ip, too many pending")
        }
    }

    /** Let this client in for as long as the server keeps running. Nothing is persisted. */
    fun allow(ip: String) {
        resolve(ip)
        addToRunningServer(ip)
    }

    /** Remember the refusal for this server session, so the user is not asked about it again. */
    fun deny(ip: String) {
        resolve(ip)
        ipsDenied.add(ip)
    }

    /**
     * Let this client in and persist it. Turning on [SHTTPSConfig.WhiteListMode.PREDEFINED] is
     * part of the deal (Android does the same): with only ASK_AT_RUNTIME set the server ignores
     * the saved list entirely, so saving an IP without it would silently do nothing.
     */
    fun allowAndSave(ip: String) {
        resolve(ip)
        val whiteList = config.getWhiteList()
        whiteList.add(ip)
        config.setWhiteList(whiteList)
        val mode = config.getWhiteListMode()
        if (!mode.contains(SHTTPSConfig.WhiteListMode.PREDEFINED)) {
            val updatedMode = HashSet(mode)
            updatedMode.add(SHTTPSConfig.WhiteListMode.PREDEFINED)
            config.setWhiteListMode(updatedMode)
        }
        addToRunningServer(ip)
        onWhiteListPersisted?.invoke()
    }

    /**
     * Applies a whitelist edited in the settings dialog to the already running server, so that
     * changing this setting does not have to bounce the server while a client is retrying.
     *
     * Everything remembered about the previous whitelist goes away with it - including open
     * prompts, whose buttons would otherwise act on a list the user has just replaced.
     */
    fun applyWhiteList(mode: Set<SHTTPSConfig.WhiteListMode>, whiteList: Set<String>) {
        ipsPending.clear()
        ipsDenied.clear()
        _prompts.clear()
        val server = shttpsApp?.server ?: return
        server.allowedClientAddresses = buildAllowedAddresses(mode, whiteList)
    }

    /**
     * Forgets everything remembered about the current server session: open prompts (there is
     * nothing left to allow) and refusals (Android keeps those only for as long as its server
     * service lives, and a stopped server ends the session here in the same way).
     */
    fun onServerStopped() {
        ipsPending.clear()
        ipsDenied.clear()
        _prompts.clear()
    }

    private fun resolve(ip: String) {
        ipsPending.remove(ip)
        _prompts.remove(ip)
    }

    /**
     * Mirrors what [SHTTPSApp.startServer] does when it builds the initial set: an empty mode
     * means no filtering at all (null), and the saved addresses only count when PREDEFINED is on.
     */
    private fun buildAllowedAddresses(
        mode: Set<SHTTPSConfig.WhiteListMode>,
        whiteList: Set<String>
    ): Set<InetAddress>? {
        if (mode.isEmpty()) return null
        val result = HashSet<InetAddress>()
        if (mode.contains(SHTTPSConfig.WhiteListMode.PREDEFINED)) {
            for (address in whiteList) {
                try {
                    result.add(InetAddress.getByName(address))
                } catch (e: UnknownHostException) {
                    logger.e("Error while processing whitelist entry $address", e)
                }
            }
        }
        return result
    }

    private fun addToRunningServer(ip: String) {
        val server = shttpsApp?.server ?: return
        val address = try {
            InetAddress.getByName(ip)
        } catch (e: UnknownHostException) {
            logger.e("Can not resolve $ip", e)
            return
        }
        //replace the whole set instead of adding into it: only the field is volatile, and the
        //accept loop calls contains() on whatever set it happens to be pointing at
        val updated = HashSet(server.allowedClientAddresses ?: emptySet())
        updated.add(address)
        server.allowedClientAddresses = updated
    }
}
