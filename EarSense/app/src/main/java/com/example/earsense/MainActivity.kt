package com.example.earsense

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.appbar.MaterialToolbar

lateinit var profiles: Array<String>

class MainActivity : AppCompatActivity() {
    lateinit var profileTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val toolBar: MaterialToolbar = findViewById(R.id.materialToolbar)
        setSupportActionBar(toolBar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // Login Button
        val buttonLogin: Button = findViewById(R.id.buttonLogin)
        buttonLogin.setOnClickListener {
            val intent = Intent(this, MainMenuActivity::class.java)
            startActivity(intent)
        }

        // Select Profile Button
        val buttonSelectProfile: Button = findViewById(R.id.buttonSelectProfile)
        buttonSelectProfile.setOnClickListener {
            showSelectProfileDialog()
        }

        profiles = loadProfiles()

        // Create default profile if no profiles exist
        if (profiles.isEmpty()) {
            createNewProfile("Demo")
            profiles = loadProfiles()
        }

        // Set default profile to the first one
        setCurrentProfile(profiles[0])

        // Update profile text
        profileTextView = findViewById(R.id.textProfile)
        profileTextView.text = "Current Profile: " + Utils.getCurrentProfile(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    fun showSelectProfileDialog() {
        val dialog = UserSelectionDialog { selectedProfile ->
            // Create new profile if it doesn't exist
            if (!profiles.contains(selectedProfile)) {
                createNewProfile(selectedProfile)
            }
            setCurrentProfile(selectedProfile)
            profileTextView.text = "Current Profile: " + Utils.getCurrentProfile(this)
        }
        dialog.show(supportFragmentManager, "UserSelectionDialog")
    }

    fun setCurrentProfile(profile: String) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        prefs.edit().putString("currentProfile", profile).apply()
    }

    fun createNewProfile(profile: String) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val editor = prefs.edit()
        profiles += profile
        editor.putStringSet("profiles", profiles.toSet())
        editor.apply()
    }

    fun loadProfiles(): Array<String> {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val profiles = prefs.getStringSet("profiles", setOf()) ?: setOf()
        return profiles.toTypedArray()
    }
}

class UserSelectionDialog(private val listener: (String) -> Unit) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        return AlertDialog.Builder(context)
            .setTitle("Select Profile")
            .setItems(profiles) { _, selected ->
                listener(profiles[selected])
            }
            .setPositiveButton("Create New Profile") { _, _ ->
                showCreateProfileDialog()
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    private fun showCreateProfileDialog() {
        val context = requireContext()
        val input = EditText(context).apply { hint = "Enter Profile Name" }

        AlertDialog.Builder(context)
            .setTitle("Create New Profile")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val profileName = input.text.toString().trim()
                if (profileName.isNotEmpty()) {
                    //Save profile name
                    listener(profileName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}