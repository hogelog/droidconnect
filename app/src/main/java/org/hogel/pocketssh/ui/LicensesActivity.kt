package org.hogel.pocketssh.ui

import android.os.Bundle
import android.view.View
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import org.hogel.pocketssh.R
import org.hogel.pocketssh.databinding.ActivityLicensesBinding
import org.hogel.pocketssh.databinding.RowLicenseSectionBinding

class LicensesActivity : AppCompatActivity() {

    private data class Section(
        @StringRes val titleResId: Int,
        @RawRes val licenseResId: Int,
    )

    // One row per bundled component. Apache 2.0 dependencies share the same
    // license text; duplication here is intentional so each library opens its
    // license directly without indirection.
    private val sections = listOf(
        Section(R.string.licenses_section_pocketsecureshell, R.raw.license_pocketsecureshell),
        Section(R.string.licenses_section_termux, R.raw.license_termux),
        Section(R.string.licenses_section_sshlib, R.raw.license_sshlib),
        Section(R.string.licenses_section_androidx, R.raw.license_apache2),
        Section(R.string.licenses_section_material, R.raw.license_apache2),
        Section(R.string.licenses_section_kotlin, R.raw.license_apache2),
    )

    private lateinit var binding: ActivityLicensesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        sections.forEach { addSection(it) }
    }

    private fun addSection(section: Section) {
        val row = RowLicenseSectionBinding.inflate(
            layoutInflater,
            binding.sectionsContainer,
            true,
        )
        row.title.setText(section.titleResId)
        row.body.text = readRaw(section.licenseResId)
        row.header.setOnClickListener {
            val expanded = row.body.visibility == View.VISIBLE
            row.body.visibility = if (expanded) View.GONE else View.VISIBLE
            row.chevron.rotation = if (expanded) 0f else 180f
        }
    }

    private fun readRaw(@RawRes resId: Int): String =
        resources.openRawResource(resId).bufferedReader().use { it.readText() }
}
