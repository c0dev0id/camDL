package de.codevoid.camdl

import android.app.Application
import de.codevoid.camdl.probe.ProbeLog

/**
 * Holds the probe log for the life of the process.
 *
 * Application-scoped rather than per-activity so a capture survives a rotation or the app
 * being backgrounded mid-connect - losing the log is losing the round trip.
 */
class CamDlApp : Application() {

    val probe = ProbeLog()

    companion object {
        fun probe(application: Application): ProbeLog = (application as CamDlApp).probe
    }
}
