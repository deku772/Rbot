"""Fix remaining botdrop refs in drawable and layout XML files."""
import os

base = r"d:\projects\CLAW\rbot\app\src\main\res"
files = [
    "drawable\\ic_service_notification.xml",
    "drawable\\ic_update.xml",
    "drawable\\model_selector_badge_bg.xml",
    "drawable\\model_selector_row_bg.xml",
    "layout\\activity_log.xml",
    "layout\\activity_main.xml",
    "layout\\activity_permissions.xml",
    "layout\\activity_settings.xml",
]

for rel_path in files:
    fp = os.path.join(base, rel_path)
    if not os.path.exists(fp):
        print(f"SKIP (not found): {rel_path}")
        continue
    with open(fp, "rb") as f:
        raw = f.read()

    # Try UTF-8 first
    try:
        content = raw.decode("utf-8")
        encoding = "utf-8"
    except UnicodeDecodeError:
        # Fall back to GBK/cp936 for Chinese Windows
        content = raw.decode("cp936", errors="replace")
        encoding = "cp936"
        print(f"  WARNING: {rel_path} decoded as cp936")

    original = content
    content = content.replace("botdrop", "rbot")
    content = content.replace("BotDrop", "Rbot")

    if content != original:
        with open(fp, "w", encoding="utf-8") as f:
            f.write(content)
        print(f"FIXED: {rel_path}")
    else:
        print(f"OK (no change): {rel_path}")

print("Done")
