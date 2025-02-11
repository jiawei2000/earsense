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

class MainMenuActivity : AppCompatActivity() {

    lateinit var profileTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main_menu)

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

        // Breathing Recognition Button
        val buttonBreathing: Button = findViewById(R.id.buttonBreathing)
        buttonBreathing.setOnClickListener {
            val intent = Intent(this, BreathingActivity::class.java)
            startActivity(intent)
        }

        // Profile TextView
        profileTextView = findViewById(R.id.textProfile)
        profileTextView.text = "Current Profile: " + Utils.getCurrentProfile(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

}