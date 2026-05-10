package moe.shizuku.manager.app

import androidx.appcompat.app.AppCompatActivity

abstract class AppActivity : AppCompatActivity() {

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) {
            finish()
        }
        return true
    }
}
