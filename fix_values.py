"""Fix styles.xml and themes.xml - replace botdrop refs with rbot."""
import os

def fix_file(path):
    if not os.path.exists(path):
        print(f"SKIP (not found): {path}")
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    original = content
    content = content.replace("botdrop", "rbot")
    content = content.replace("BotDrop", "Rbot")
    if content != original:
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        print(f"FIXED: {path}")
    else:
        print(f"OK (no change): {path}")

fix_file(r"d:\projects\CLAW\rbot\app\src\main\res\values\styles.xml")
fix_file(r"d:\projects\CLAW\rbot\app\src\main\res\values\themes.xml")
fix_file(r"d:\projects\CLAW\rbot\app\src\main\res\values-night\themes.xml")
fix_file(r"d:\projects\CLAW\rbot\app\src\main\res\values\colors.xml")
fix_file(r"d:\projects\CLAW\rbot\app\src\main\res\values\strings.xml")
fix_file(r"d:\projects\CLAW\rbot\app\src\main\res\values-zh-rCN\strings.xml")
print("Done")
