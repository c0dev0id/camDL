package de.codevoid.camdl.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import de.codevoid.camdl.CamDlApp
import de.codevoid.camdl.databinding.ActivityLogBinding
import de.codevoid.camdl.probe.ProbeExport

/**
 * The probe log as text, with a share action.
 *
 * Monospaced and not wrapped, because hexdump columns that reflow are unreadable. The
 * horizontal scroll in the layout is what makes that work on a phone.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val probe = CamDlApp.probe(application)
        binding.log.text = ProbeExport.render(probe)

        binding.share.setOnClickListener {
            startActivity(ProbeExport.shareIntent(this, probe))
        }
    }
}
