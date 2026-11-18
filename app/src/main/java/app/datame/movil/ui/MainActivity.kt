package app.datame.movil.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** One screen: pairing until the phone has a house, then the state and the settings. */
class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DatameTheme {
                val config by model.config.collectAsStateWithLifecycle()
                if (config.paired) HomeScreen(model) else PairScreen(model)
            }
        }
    }
}
