package com.blizzardcaron.freeolleefaces

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.blizzardcaron.freeolleefaces.ui.theme.FreeOlleeFacesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FreeOlleeFacesTheme {
                Scaffold { inner ->
                    PlaceholderScreen(Modifier.padding(inner))
                }
            }
        }
    }
}

@Composable
fun PlaceholderScreen(modifier: Modifier = Modifier) {
    Text("FreeOllee Faces — scaffold OK", modifier = modifier)
}
