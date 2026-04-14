"""Fix RbotActivity.java - routing logic for bot-agnostic check."""
import re

filepath = r"d:\projects\CLAW\rbot\app\src\main\java\app\rbot\RbotActivity.java"

with open(filepath, "r", encoding="utf-8") as f:
    content = f.read()

old_block = """        // Check 3: AstrBot installed?
        if (!ChrootManager.isAstrBotInstalled()) {
            Log.i(TAG, "AstrBot not installed, routing to setup");
            mStatusText.setText(R.string.rbot_setup_required);
            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_INSTALL);
            startActivity(intent);
            finish();
            return;
        }

        // All ready 鈥?go to main
        Log.i(TAG, "All ready, routing to main");
        mStatusText.setText(R.string.rbot_starting_status);
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}"""

new_block = """        // Check 3: Active bot installed?
        BotAdapter activeBot = BotManager.getInstance(this).getActiveBot();
        if (!activeBot.isInstalled()) {
            Log.i(TAG, activeBot.getName() + " not installed, routing to setup");
            mStatusText.setText(R.string.rbot_setup_required);
            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra(SetupActivity.EXTRA_START_STEP, SetupActivity.STEP_INSTALL);
            startActivity(intent);
            finish();
            return;
        }

        // All ready — route to the active bot's management panel
        Log.i(TAG, "All ready, routing to " + activeBot.getName() + " panel");
        mStatusText.setText(R.string.rbot_starting_status);
        Intent intent;
        if (activeBot.getId().equals(BotAdapter.ID_HERMES)) {
            intent = new Intent(this, HermesManagementActivity.class);
        } else {
            intent = new Intent(this, MainActivity.class);
        }
        startActivity(intent);
        finish();
    }
}"""

if old_block in content:
    content = content.replace(old_block, new_block)
    print("RbotActivity.java: block replaced successfully")
else:
    print("ERROR: old block not found in RbotActivity.java")
    idx = content.find("Check 3")
    if idx >= 0:
        print("Found 'Check 3' at index", idx)
        ctx = content[idx:idx+600]
        print("Context:", repr(ctx))

with open(filepath, "w", encoding="utf-8") as f:
    f.write(content)
