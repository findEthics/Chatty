package com.example.chatty

import android.content.SharedPreferences
import android.os.Bundle
import androidx.core.content.edit
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var queryInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var resetButton: ImageButton
    private lateinit var titleText: TextView
    private lateinit var adapter: MessageAdapter
    private val messages = mutableListOf<Message>()
    private var currentModel = AIModel.ATLAS
    private lateinit var aiClient: AIClient
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var toolbar: Toolbar
    private lateinit var loadingProgressBar: TextView
    private lateinit var themePrefs: SharedPreferences
    private lateinit var darkModeSwitch: SwitchCompat
    private lateinit var customNavToggle: ImageButton

    companion object {
        private const val THEME_PREFS = "theme_prefs"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_FONT_SIZE = "font_size"
    }
    
    enum class FontSize {
        SMALL, REGULAR, LARGE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize theme preferences
        themePrefs = getSharedPreferences(THEME_PREFS, MODE_PRIVATE)
        applyTheme()
        
        setContentView(R.layout.activity_main)

        aiClient = AIClient()
        initializeViews()
        setupRecyclerView()

        // Setup navigation drawer (without automatic toggle)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        setupClickListeners()

        setupModelSwitch()
    }

    private fun initializeViews() {
        recyclerView = findViewById(R.id.messagesRecycler)
        queryInput = findViewById(R.id.queryInput)
        sendButton = findViewById(R.id.sendButton)
        resetButton = findViewById(R.id.resetButton)
        titleText = findViewById(R.id.titleText)
        drawerLayout = findViewById(R.id.drawer_layout)
        navView = findViewById(R.id.nav_view)
        toolbar = findViewById(R.id.toolbar)
        loadingProgressBar = findViewById(R.id.loadingProgressBar)
        
        // Initialize dark mode switch from navigation header
        val headerView = navView.getHeaderView(0)
        darkModeSwitch = headerView.findViewById(R.id.darkModeSwitch)
        
        // Initialize custom navigation toggle
        customNavToggle = findViewById(R.id.customNavToggle)
    }

    private fun setupRecyclerView() {
        adapter = MessageAdapter(messages)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupClickListeners() {
        sendButton.setOnClickListener {
            val query = queryInput.text.toString().trim()
            if (query.isNotEmpty()) {
                handleQuery(query)
            }
        }

        resetButton.setOnClickListener {
            resetSession()
        }
        
        // Setup custom navigation toggle
        customNavToggle.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        // Handle navigation item clicks
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_api_key_perplexity -> {
                    showApiKeyDialog(AIModel.PERPLEXITY)
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.nav_font_size -> {
                    showFontSizeDialog()
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                else -> false
            }
        }
        
        // Setup dark mode switch
        setupDarkModeSwitch()
        
        // Apply current font size
        applyFontSize()
    }

    private fun setupModelSwitch() {
        val modelSwitchButton = findViewById<Button>(R.id.modelSwitch)
        modelSwitchButton.text = currentModel.name

        modelSwitchButton.setOnClickListener {
            currentModel = when (currentModel) {
                AIModel.PERPLEXITY -> AIModel.ATLAS
                AIModel.ATLAS -> AIModel.PERPLEXITY
            }
            modelSwitchButton.text = currentModel.name
            // Optional: You might want to show a Toast or log the change
            Toast.makeText(this, "Switched to ${currentModel.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleQuery(query: String) {
        loadingProgressBar.visibility = View.VISIBLE
        titleText.visibility = View.GONE
        sendButton.isEnabled = false
        
        // Immediately display the user question
        messages.add(Message(query, ""))
        adapter.notifyItemInserted(messages.size - 1)
        recyclerView.smoothScrollToPosition(messages.size - 1)
        queryInput.text.clear()
        resetButton.visibility = View.VISIBLE
        
        val apiKey = aiClient.loadApiKey(this, currentModel)
        if (apiKey.isEmpty() && currentModel != AIModel.ATLAS) {
            showApiKeyDialog(currentModel)
            loadingProgressBar.visibility = View.GONE
            sendButton.isEnabled = true
            // Remove the message with empty response if API key dialog is shown
            messages.removeAt(messages.size - 1)
            adapter.notifyItemRemoved(messages.size)
            if (messages.isEmpty()) {
                titleText.visibility = View.VISIBLE
                resetButton.visibility = View.GONE
            }
        } else {
            println("handleQuery")
            sendQueryToApi(query, apiKey)
        }
    }

    private fun sendQueryToApi(query: String, apiKey: String) {
        CoroutineScope(Dispatchers.IO).launch {
            println(currentModel)
            try {
                // Get the current model selection
                val response = when (currentModel) { // Use the class property currentModel
                    AIModel.PERPLEXITY -> aiClient.queryPerplexity(query, apiKey)
                    AIModel.ATLAS -> aiClient.queryEthicsAtlas(query)
                }
                withContext(Dispatchers.Main) {
                    updateUIWithResponse(query, response)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val errorMsg = when (e) {
                        is IllegalArgumentException -> "Invalid API key"
                        else -> "API error: ${e.localizedMessage}"
                    }
                    println("sendQueryToApi ${e.message}")
                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_SHORT).show()
                    loadingProgressBar.visibility = View.GONE
                    sendButton.isEnabled = true
                    
                    // Update the last message with error response
                    if (messages.isNotEmpty()) {
                        val lastIndex = messages.size - 1
                        val query = messages[lastIndex].query
                        messages[lastIndex] = Message(query, "Error: $errorMsg")
                        adapter.notifyItemChanged(lastIndex)
                    }
                }
            }
        }
    }

    private fun updateUIWithResponse(query: String, response: String) {
        loadingProgressBar.visibility = View.GONE
        sendButton.isEnabled = true
        titleText.visibility = View.VISIBLE
        
        // Update the last message with the response
        if (messages.isNotEmpty()) {
            val lastIndex = messages.size - 1
            messages[lastIndex] = Message(query, response)
            adapter.notifyItemChanged(lastIndex)
            recyclerView.smoothScrollToPosition(lastIndex)
        }
    }

    private fun showApiKeyDialog(model: AIModel) {
        loadingProgressBar.visibility = View.GONE
        sendButton.isEnabled = true
        val input = EditText(this)
        input.hint = "Enter API Key for ${model.name}"
        AlertDialog.Builder(this)
            .setTitle("${model.name} API Key")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    aiClient.saveApiKey(this, key, model)
                    Toast.makeText(this, "API Key for ${model.name} saved.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "API Key cannot be empty.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    private fun resetSession() {
        val itemCount = messages.size
        messages.clear()
        adapter.notifyItemRangeRemoved(0, itemCount)
        titleText.visibility = View.VISIBLE
        resetButton.visibility = View.GONE
        loadingProgressBar.visibility = View.GONE
        sendButton.isEnabled = true
    }

    inner class MessageAdapter(private val messages: List<Message>) :
        RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

        inner class MessageViewHolder(itemView: View) :
            RecyclerView.ViewHolder(itemView) {
            val queryText: TextView = itemView.findViewById(R.id.queryText)
            val responseText: TextView = itemView.findViewById(R.id.responseText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_message, parent, false)
            return MessageViewHolder(view)
        }

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            val message = messages[position]
            holder.queryText.text = message.query
            holder.responseText.text = message.response
            
            // Apply dynamic font sizes
            holder.queryText.textSize = getQueryTextSize()
            holder.responseText.textSize = getResponseTextSize()
        }

        override fun getItemCount() = messages.size
    }

    private fun applyTheme() {
        val isDarkMode = themePrefs.getBoolean(KEY_DARK_MODE, false)
        if (isDarkMode) {
            setTheme(R.style.Theme_Chatty_Dark)
        } else {
            setTheme(R.style.Theme_Chatty)
        }
    }

    private fun setupDarkModeSwitch() {
        val isDarkMode = themePrefs.getBoolean(KEY_DARK_MODE, false)
        darkModeSwitch.isChecked = isDarkMode
        
        darkModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            themePrefs.edit {
                putBoolean(KEY_DARK_MODE, isChecked)
            }
            // Recreate activity to apply new theme
            recreate()
        }
    }

    private fun showFontSizeDialog() {
        val currentFontSize = getCurrentFontSize()
        val fontSizeOptions = arrayOf("Small", "Regular", "Large")
        val currentSelection = when (currentFontSize) {
            FontSize.SMALL -> 0
            FontSize.REGULAR -> 1
            FontSize.LARGE -> 2
        }

        AlertDialog.Builder(this)
            .setTitle("Font Size")
            .setSingleChoiceItems(fontSizeOptions, currentSelection) { dialog, which ->
                val selectedFontSize = when (which) {
                    0 -> FontSize.SMALL
                    1 -> FontSize.REGULAR
                    2 -> FontSize.LARGE
                    else -> FontSize.REGULAR
                }
                saveFontSize(selectedFontSize)
                applyFontSize()
                dialog.dismiss()
                Toast.makeText(this, "Font size updated to ${fontSizeOptions[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun getCurrentFontSize(): FontSize {
        val fontSizeName = themePrefs.getString(KEY_FONT_SIZE, FontSize.REGULAR.name)
        return FontSize.valueOf(fontSizeName ?: FontSize.REGULAR.name)
    }

    private fun saveFontSize(fontSize: FontSize) {
        themePrefs.edit {
            putString(KEY_FONT_SIZE, fontSize.name)
        }
    }

    private fun applyFontSize() {
        val fontSize = getCurrentFontSize()
        
        when (fontSize) {
            FontSize.SMALL -> {
                titleText.textSize = 14f
                loadingProgressBar.textSize = 14f
            }
            FontSize.REGULAR -> {
                titleText.textSize = 18f
                loadingProgressBar.textSize = 18f
            }
            FontSize.LARGE -> {
                titleText.textSize = 22f
                loadingProgressBar.textSize = 22f
            }
        }
        
        // Update adapter to refresh message text sizes
        adapter.notifyItemRangeChanged(0, messages.size)
    }

    fun getQueryTextSize(): Float {
        val fontSize = getCurrentFontSize()
        return when (fontSize) {
            FontSize.SMALL -> 14f
            FontSize.REGULAR -> 18f
            FontSize.LARGE -> 22f
        }
    }

    fun getResponseTextSize(): Float {
        val fontSize = getCurrentFontSize()
        return when (fontSize) {
            FontSize.SMALL -> 14f
            FontSize.REGULAR -> 18f
            FontSize.LARGE -> 22f
        }
    }
}


