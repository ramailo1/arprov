import os
import re

def fix_plugin_files_comprehensive():
    """Comprehensive fix for Plugin.kt files"""
    print("🔧 إصلاح شامل لملفات Plugin.kt...")
    
    for root, dirs, files in os.walk('.'):
        for file in files:
            if file.endswith('Plugin.kt'):
                plugin_path = os.path.join(root, file)
                
                with open(plugin_path, 'r', encoding='utf-8') as f:
                    content = f.read()
                
                # Create new content from scratch
                provider_name = file.replace('Plugin.kt', '')
                package_name = plugin_path.split('kotlin' + os.sep)[1].replace('Plugin.kt', '').replace(os.sep, '.').strip('.')
                
                new_content = f"""package {package_name}

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import android.content.Context

@CloudstreamPlugin
class {provider_name}Plugin : BasePlugin() {{
    override fun load() {{
        registerMainAPI({provider_name}())
    }}
}}
"""
                
                with open(plugin_path, 'w', encoding='utf-8') as f:
                    f.write(new_content)
                print(f"  ✅ تم إعادة إنشاء {plugin_path}")

def fix_manifest_files():
    """Fix AndroidManifest.xml files completely"""
    print("\n🔧 إصلاح كامل لملفات AndroidManifest.xml...")
    
    for root, dirs, files in os.walk('.'):
        for file in files:
            if file == 'AndroidManifest.xml':
                manifest_path = os.path.join(root, file)
                
                # Extract package name from directory structure
                parts = root.split(os.sep)
                if 'kotlin' in parts and 'com' in parts:
                    com_index = parts.index('com')
                    package_parts = parts[com_index:]
                    package_name = '.'.join(package_parts)
                else:
                    package_name = "com.lagradost.cloudstream3"
                
                new_manifest = f"""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{package_name}">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application>
        <meta-data
            android:name="com.google.android.gms.version"
            android:value="@integer/google_play_services_version" />
    </application>

</manifest>
"""
                
                with open(manifest_path, 'w', encoding='utf-8') as f:
                    f.write(new_manifest)
                print(f"  ✅ تم إعادة إنشاء {manifest_path}")

def fix_provider_imports_complete():
    """Complete fix for Provider.kt files"""
    print("\n🔧 إصلاح كامل لملفات Provider.kt...")
    
    for root, dirs, files in os.walk('.'):
        for file in files:
            if file.endswith('Provider.kt') and 'Plugin' not in file:
                provider_path = os.path.join(root, file)
                
                with open(provider_path, 'r', encoding='utf-8') as f:
                    content = f.read()
                
                # Extract package name
                package_match = re.search(r'package\s+([\w.]+)', content)
                if package_match:
                    package_name = package_match.group(1)
                else:
                    parts = root.split(os.sep)
                    if 'kotlin' in parts and 'com' in parts:
                        com_index = parts.index('com')
                        package_parts = parts[com_index:]
                        package_name = '.'.join(package_parts)
                    else:
                        package_name = "com.lagradost.cloudstream3"
                
                # Extract provider name
                provider_name = file.replace('.kt', '')
                
                # Check if MainAPI is properly declared
                if not re.search(r'class\s+' + provider_name + r'\s*:\s*MainAPI', content):
                    # Find the class declaration and fix it
                    content = re.sub(
                        r'class\s+' + provider_name + r'\s*\([^)]*\)',
                        f'class {provider_name} : MainAPI()',
                        content
                    )
                
                # Ensure proper imports
                required_imports = [
                    'com.lagradost.cloudstream3.MainAPI',
                    'com.lagradost.cloudstream3.TvType',
                    'com.lagradost.cloudstream3.LoadResponse',
                    'com.lagradost.cloudstream3.SearchResponse',
                    'com.lagradost.cloudstream3.HomePageResponse',
                    'com.lagradost.cloudstream3.Episode',
                    'com.lagradost.cloudstream3.ExtractorLink',
                    'com.lagradost.cloudstream3.SubtitleFile'
                ]
                
                # Add package declaration if missing
                if not content.startswith('package'):
                    content = f"package {package_name}\n\n{content}"
                
                # Add imports after package
                for import_stmt in required_imports:
                    class_name = import_stmt.split('.')[-1]
                    if class_name in content and import_stmt not in content:
                        content = content.replace(
                            f'package {package_name}',
                            f'package {package_name}\n\nimport {import_stmt}'
                        )
                
                with open(provider_path, 'w', encoding='utf-8') as f:
                    f.write(content)
                print(f"  ✅ تم تحديث {provider_path}")

def main():
    print('🔧 إصلاح شامل لجميع المشاكل المتبقية')
    print('=' * 60)
    
    fix_plugin_files_comprehensive()
    fix_manifest_files()
    fix_provider_imports_complete()
    
    print(f"\n✅ تم اكتمال الإصلاحات الشاملة!")

if __name__ == '__main__':
    main()