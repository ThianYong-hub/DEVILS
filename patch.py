import re

file = 'devils-addon/src/main/java/com/devils/addon/util/smoke/AssimilatedInteractionChecks.java'
content = open(file, 'r', encoding='utf-8').read()

content = re.sub(r'\bpublic\s*public\b', 'public', content)
content = re.sub(r'public\s+public\s+public', 'public', content)
content = re.sub(r'\bpublic\s+\n', '\n', content)

# I also need to make sure I delete the rest of the xaero checks like `whereIsItRequestFlow`, `xaeroRefreshHookFlow`, `xaeroPlusSettingLifecycle`. Wait! I missed them because they didn't match the list exactly.
content = re.sub(r'public static SmokeCheckResult whereIsItRequestFlow\(.*?\).*?return SmokeCheckResult\.pass.*?\n    }\n', '', content, flags=re.DOTALL)
content = re.sub(r'public static SmokeCheckResult xaeroRefreshHookFlow\(.*?\).*?return SmokeCheckResult\.pass.*?\n    }\n', '', content, flags=re.DOTALL)
content = re.sub(r'public static SmokeCheckResult xaeroPlusSettingLifecycle\(.*?\).*?return SmokeCheckResult\.pass.*?\n    }\n', '', content, flags=re.DOTALL)

# Let me just clean the file aggressively using regex.
open(file, 'w', encoding='utf-8').write(content)
print("Done")
