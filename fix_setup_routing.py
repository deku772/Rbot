"""Fix SetupActivity.java - route to correct panel after install."""
filepath = r"d:\projects\CLAW\rbot\app\src\main\java\app\rbot\SetupActivity.java"

with open(filepath, "r", encoding="utf-8") as f:
    content = f.read()

old_block = """            appendLog("\u27a1\ufe0f 正在跳转到主界面...");
            Intent intent = new Intent(SetupActivity.this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    // \u2500\u2500\u2500 Rootfs tarball finding \u2500\u2500\u2500"""

new_block = """            appendLog("\u27a1\ufe0f 正在跳转到主界面...");
            Class<?> targetActivity;
            if (mPendingBot.getId().equals(BotAdapter.ID_HERMES)) {
                targetActivity = HermesManagementActivity.class;
            } else {
                targetActivity = MainActivity.class;
            }
            Intent intent = new Intent(SetupActivity.this, targetActivity);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    // \u2500\u2500\u2500 Rootfs tarball finding \u2500\u2500\u2500"""

if old_block in content:
    content = content.replace(old_block, new_block)
    print("SetupActivity.java: replaced OK")
else:
    print("ERROR: old block not found in SetupActivity.java")

with open(filepath, "w", encoding="utf-8") as f:
    f.write(content)
