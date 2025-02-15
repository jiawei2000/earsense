package com.example.earsense

import android.os.Bundle
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar

val activityTypes = arrayOf("Walking", "Running", "Speaking", "Still")

class ActivityTrainingActivity : AppCompatActivity() {

    lateinit var viewPager: ViewPager2

    lateinit var currentProfile: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_activity_training)

        //Tool bar
        val toolBar: MaterialToolbar = findViewById(R.id.materialToolbar)
        setSupportActionBar(toolBar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        toolBar.setTitleTextColor(ContextCompat.getColor(this, R.color.white))
        supportActionBar?.title = "Activity Recognition Training"
        toolBar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // View Pager
        viewPager = findViewById(R.id.viewPager)
        val pageAdapter = ActivityScreenSlidePagerAdapter(this)
        viewPager.adapter = pageAdapter
        viewPager.isUserInputEnabled = false

        //Next button
        val buttonNext: Button = findViewById(R.id.buttonNext)
        buttonNext.setOnClickListener {
            viewPager.currentItem = viewPager.currentItem.plus(1)
        }
        //Disable next button on last page
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                buttonNext.isEnabled = position != activityTypes.size - 1
            }
        })

        //Back button
        val buttonBack: Button = findViewById(R.id.buttonBack)
        buttonBack.setOnClickListener {
            viewPager.currentItem = viewPager.currentItem.minus(1)
        }
        // Disable back button on first page
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                buttonBack.isEnabled = position != 0
            }
        })

        // Done button
        val buttonDone: Button = findViewById(R.id.buttonTrain)
        buttonDone.setOnClickListener {
//            trainModel()
        }

        currentProfile = Utils.getCurrentProfile(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
}

class ActivityScreenSlidePagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
    override fun getItemCount(): Int = activityTypes.size

    override fun createFragment(position: Int): Fragment {

        val step1 = ActivityTrainingFragment.newInstance(activityTypes[0])
        val step2 = ActivityTrainingFragment.newInstance(activityTypes[1])
        val step3 = ActivityTrainingFragment.newInstance(activityTypes[2])
        val step4 = ActivityTrainingFragment.newInstance(activityTypes[3])

        return when (position) {
            0 -> step1
            1 -> step2
            2 -> step3
            3 -> step4
            else -> BreathingTrainingFragment()
        }
    }
}