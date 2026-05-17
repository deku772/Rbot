package app.rbot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import app.rbot.ui.navigation.RbotNavHost
import app.rbot.ui.theme.RbotTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 单 Activity 架构的主入口。
 * 所有页面通过 Navigation Compose 在此 Activity 内导航。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RbotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RbotNavHost()
                }
            }
        }
    }
}
