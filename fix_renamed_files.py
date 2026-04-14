"""Recover and properly rename botdrop XML files from git."""
import subprocess

# Recover original content from git as raw bytes, decode as UTF-8
def get_from_git(path):
    result = subprocess.run(
        ['git', 'show', f'HEAD:{path}'],
        capture_output=True
    )
    if result.returncode != 0:
        raise RuntimeError(f"git show failed for {path}: {result.stderr}")
    return result.stdout.decode('utf-8')

# Fix one file: get original, do replacement, write to new path
def fix_rename(src_path, dst_path):
    content = get_from_git(src_path)
    # botdrop -> rbot (lowercase)
    content = content.replace('botdrop', 'rbot')
    # BotDrop -> Rbot (title case)
    content = content.replace('BotDrop', 'Rbot')
    with open(dst_path, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f'  Created {dst_path} ({len(content)} bytes)')

# Restore the two deleted layout files with proper renaming
fix_rename(
    'app/src/main/res/layout/activity_botdrop_launcher.xml',
    'app/src/main/res/layout/activity_rbot_launcher.xml'
)
fix_rename(
    'app/src/main/res/layout/activity_botdrop_setup_minimal.xml',
    'app/src/main/res/layout/activity_rbot_setup_minimal.xml'
)
print('Done!')
