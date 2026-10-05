package com.phlox.simpleserver.security

import androidx.compose.runtime.mutableStateListOf
import com.phlox.simpleserver.screenshare.ScreenAccessApprover
import com.phlox.simpleserver.screenshare.ScreenAccessRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The desktop side of "Confirm remote screen connections": turns each viewer the screen stream
 * endpoint is holding at the door into a prompt window, and hands the user's answer back to it.
 * The Android counterpart is the notification `ServerService` posts.
 *
 * Lives above the navigation graph next to [ClientApprovalController], for the same reason: the
 * prompts have to show while the main window sits hidden in the tray. Unlike that one it keeps no
 * state of its own beyond what is on screen - which requests are pending, and what a reconnecting
 * client is let in with, is the endpoint's business.
 */
class ScreenAccessApprovalController : ScreenAccessApprover {
    //the requests arrive on connection threads and leave from wherever they were settled; the list
    //is snapshot state, so every change is moved onto the UI thread, in the order it happened
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _prompts = mutableStateListOf<ScreenAccessRequest>()
    val prompts: List<ScreenAccessRequest> get() = _prompts

    override fun onAccessRequested(request: ScreenAccessRequest) {
        uiScope.launch {
            //the client may have gone, or the question timed out, before this got its turn
            if (!request.isSettled) {
                _prompts.add(request)
            }
        }
    }

    override fun onAccessRequestFinished(request: ScreenAccessRequest) {
        uiScope.launch { _prompts.remove(request) }
    }

    fun allow(request: ScreenAccessRequest) = answer(request, ScreenAccessRequest.Decision.ALLOW)

    fun allowViewOnly(request: ScreenAccessRequest) =
        answer(request, ScreenAccessRequest.Decision.VIEW_ONLY)

    fun deny(request: ScreenAccessRequest) = answer(request, ScreenAccessRequest.Decision.DENY)

    /**
     * The server is gone and with it every connection a prompt was about. Their requests are
     * settled as each connection closes; this only makes sure no window outlives them.
     */
    fun onServerStopped() {
        uiScope.launch { _prompts.clear() }
    }

    private fun answer(request: ScreenAccessRequest, decision: ScreenAccessRequest.Decision) {
        //called on the UI thread; drop the window now rather than one dispatch later
        _prompts.remove(request)
        request.answer(decision)
    }
}
