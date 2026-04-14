"""
Replace all botdrop references with rbot in the rbot Android project.
Handles: botdrop -> rbot, BotDrop -> Rbot
"""
import os
import re

PROJECT_ROOT = r"d:\projects\CLAW\rbot"

# File extensions to process
EXTENSIONS = {".java", ".xml", ".kt", ".gradle", ".properties", ".md"}

# Directories to skip
SKIP_DIRS = {".gradle", "build", ".idea", "app/build", "termux-shared/build",
             "terminal-emulator/build", "terminal-view/build", "manager/build",
             "server/build", "starter/build", "common/build", "api/build",
             "node_modules", ".git"}

def should_skip(path):
    parts = path.replace(PROJECT_ROOT, "").split(os.sep)
    return any(part in SKIP_DIRS for part in parts)

def replace_content(filepath):
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    original = content

    # botdrop -> rbot (lowercase)
    content = content.replace("botdrop", "rbot")
    # BotDrop -> Rbot (title case)
    content = content.replace("BotDrop", "Rbot")

    if content != original:
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(content)
        return True
    return False

def main():
    changed = []
    for root, dirs, files in os.walk(PROJECT_ROOT):
        # Skip hidden and build dirs
        dirs[:] = [d for d in dirs if not d.startswith(".") and d not in SKIP_DIRS]

        for file in files:
            ext = os.path.splitext(file)[1].lower()
            if ext not in EXTENSIONS:
                continue
            filepath = os.path.join(root, file)
            if should_skip(filepath):
                continue
            try:
                if replace_content(filepath):
                    changed.append(filepath)
            except Exception as e:
                print(f"ERROR: {filepath}: {e}")

    print(f"\nReplaced in {len(changed)} files:\n")
    for f in changed:
        print(f"  {f.replace(PROJECT_ROOT, '')}")

if __name__ == "__main__":
    main()
