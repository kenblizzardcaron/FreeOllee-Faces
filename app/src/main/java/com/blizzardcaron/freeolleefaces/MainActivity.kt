package com.blizzardcaron.freeolleefaces

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.blizzardcaron.freeolleefaces.ui.MainScreen
import com.blizzardcaron.freeolleefaces.ui.MainScreenCallbacks
import com.blizzardcaron.freeolleefaces.ui.MainScreenState
import com.blizzardcaron.freeolleefaces.ui.theme.FreeOlleeFacesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FreeOlleeFacesTheme {
                Scaffold { inner ->
                    var state by remember { mutableStateOf(MainScreenState()) }
                    val callbacks = MainScreenCallbacks(
                        onLatChange = { state = state.copy(lat = it) },
                        onLngChange = { state = state.copy(lng = it) },
                        onCustomChange = { state = state.copy(custom = it) },
                        onSelectWatch = { /* wired in Task 11 */ },
                        onUseMyLocation = { /* wired in Task 12 */ },
                        onSendTemperature = { /* wired in Task 12 */ },
                        onSendSunTime = { /* wired in Task 12 */ },
                        onSendCustom = { /* wired in Task 12 */ },
                    )
                    MainScreen(state = state, callbacks = callbacks, modifier = Modifier.padding(inner))
                }
            }
        }
    }
}
