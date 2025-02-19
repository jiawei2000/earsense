package com.example.earsense

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import java.io.File

class MainMenuActivity : AppCompatActivity() {

    lateinit var profileTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main_menu)

        val currentProfile = Utils.getCurrentProfile(this)

        val toolBar: MaterialToolbar = findViewById(R.id.materialToolbar)
        setSupportActionBar(toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolBar.setTitleTextColor(ContextCompat.getColor(this, R.color.white))
        supportActionBar?.title = "Main Menu"
        //Back button
        toolBar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Step Tracker Button
        val buttonStepTracker: Button = findViewById(R.id.buttonStepTracker)
        buttonStepTracker.setOnClickListener {
            val intent = Intent(this, StepTrackerActivity::class.java)
            startActivity(intent)
        }

        // Face Gesture Button
        val buttonGestures: Button = findViewById(R.id.buttonGesture)
        buttonGestures.setOnClickListener {
            val intent = Intent(this, GestureActivity::class.java)
            startActivity(intent)
        }

        // Activity Recognition Button
        val buttonActivity: Button = findViewById(R.id.buttonActivity)
        buttonActivity.setOnClickListener {
            val intent = Intent(this, ActivityRecognitionActivity::class.java)
            startActivity(intent)
        }

        // Delete Profile Button
        val buttonDeleteProfile: Button = findViewById(R.id.buttonDeleteProfile)
        buttonDeleteProfile.setOnClickListener {
            deleteProfileFolder(currentProfile)
            deleteProfile(currentProfile)
            // Go back to the main activity
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)

        }

        // Profile TextView
        profileTextView = findViewById(R.id.textProfile)
        profileTextView.text = "Current Profile: $currentProfile"

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    fun deleteProfile(profile: String) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val editor = prefs.edit()

        // Load the existing profiles
        val profiles = prefs.getStringSet("profiles", setOf())?.toMutableSet() ?: mutableSetOf()

        // Remove the profile
        if (profiles.contains(profile)) {
            profiles.remove(profile)

            // Update the profiles list in SharedPreferences
            editor.putStringSet("profiles", profiles)

            // If the current profile is the one being deleted, set it to null
            val currentProfile = prefs.getString("currentProfile", null)
            if (currentProfile == profile) {
                editor.remove("currentProfile") // Remove the current profile
            }

            editor.apply() // Apply changes to SharedPreferences
        }
    }

    fun deleteProfileFolder(profile: String){
        val directory = File(filesDir, profile)
        if (directory.exists()) {
            // Recursively delete all files and subdirectories
            directory.deleteRecursively()
        }
    }


}