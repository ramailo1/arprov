import os
import re

def fix_manifest_permissions():
    """Fix missing permissions in AndroidManifest.xml files"""
    print("🔧 إصلاح تصاريح AndroidManifest.xml...")
    
    for root, dirs, files in os.walk('.'):
        for file in files:
            if file == 'AndroidManifest.xml':
                manifest_path = os.path.join(root, file)
                
                with open(manifest_path, 'r', encoding='utf-8') as f:
                    content = f.read()
                
                original_content = content
                
                # Fix package declaration
                if 'package="com.' not in content:
                    # Extract package name from directory structure
                    parts = root.split(os.sep)
                    if 'kotlin' in parts and 'com' in parts:
                        com_index = parts.index('com')
                        package_parts = parts[com_index:]
                        package_name = '.'.join(package_parts)
                        
                        if 'manifest' in content and 'package=' not in content:
                            content = content.replace(
                                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
                                f'<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    package="{package_name}">'
                            )
                
                # Add missing permissions
                if 'INTERNET' not in content:
                    if '<uses-permission' not in content:
                        content = content.replace(
                            '<manifest',
                            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    package="com.lagradost.cloudstream3">\n\n    <uses-permission android:name="android.permission.INTERNET" />'
                        )
                    else:
                        content = content.replace(
                            '</manifest>',
                            '    <uses-permission android:name="android.permission.INTERNET" />\n</manifest>'
                        )
                
                if 'ACCESS_NETWORK_STATE' not in content:
                    content = content.replace(
                        '</manifest>',
                        '    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />\n</manifest>'
                    )
                
                # Add ProviderInstaller
                if 'ProviderInstaller' not in content:
                    if '</application>' in content:
                        content = content.replace(
                            '</application>',
                            '        <meta-data\n            android:name="com.google.android.gms.version"\n            android:value="@integer/google_play_services_version" />\n\n</application>'
                        )
                
                if content != original_content:
                    with open(manifest_path, 'w', encoding='utf-8') as f:
                        f.write(content)
                    print(f"  ✅ تم تحديث {manifest_path}")

def fix_plugin_imports():
    """Fix missing imports in Plugin.kt files"""
    print("\n🔧 إصلاح استيرادات Plugin.kt...")
    
    for root, dirs, files in os.walk('.'):
        for file in files:
            if file.endswith('Plugin.kt'):
                plugin_path = os.path.join(root, file)
                
                with open(plugin_path, 'r', encoding='utf-8') as f:
                    content = f.read()
                
                original_content = content
                
                # Fix imports
                required_imports = [
                    'import com.lagradost.cloudstream3.plugins.CloudstreamPlugin',
                    'import com.lagradost.cloudstream3.plugins.BasePlugin',
                    'import android.content.Context'
                ]
                
                for import_stmt in required_imports:
                    if import_stmt not in content:
                        # Add after package declaration
                        lines = content.split('\n')
                        new_lines = []
                        package_added = False
                        
                        for line in lines:
                            new_lines.append(line)
                            if line.startswith('package ') and not package_added:
                                new_lines.append('')
                                new_lines.append(import_stmt)
                                package_added = True
                        
                        content = '\n'.join(new_lines)
                
                # Fix class declaration
                if 'class' in content and 'Plugin' in content:
                    if 'BasePlugin' not in content:
                        content = content.replace('Plugin()', 'BasePlugin()')
                
                # Fix load method signature
                if 'override fun load(context: Context)' in content:
                    content = content.replace('override fun load(context: Context)', 'override fun load()')
                
                if content != original_content:
                    with open(plugin_path, 'w', encoding='utf-8') as f:
                        f.write(content)
                    print(f"  ✅ تم تحديث {plugin_path}")

def fix_provider_imports():
    """Fix missing imports in Provider.kt files"""
    print("\n🔧 إصلاح استيرادات Provider.kt...")
    
    for root, dirs, files in os.walk('.'):
        for file in files:
            if file.endswith('Provider.kt') and 'Plugin' not in file:
                provider_path = os.path.join(root, file)
                
                with open(provider_path, 'r', encoding='utf-8') as f:
                    content = f.read()
                
                original_content = content
                
                # Add missing imports
                required_imports = [
                    'import com.lagradost.cloudstream3.MainAPI',
                    'import com.lagradost.cloudstream3.TvType',
                    'import com.lagradost.cloudstream3.LoadResponse',
                    'import com.lagradost.cloudstream3.SearchResponse',
                    'import com.lagradost.cloudstream3.HomePageResponse',
                    'import com.lagradost.cloudstream3.Episode',
                    'import com.lagradost.cloudstream3.ExtractorLink',
                    'import com.lagradost.cloudstream3.SubtitleFile'
                ]
                
                for import_stmt in required_imports:
                    class_name = import_stmt.split('.')[-1]
                    if class_name in content and import_stmt not in content:
                        # Add after package declaration
                        lines = content.split('\n')
                        new_lines = []
                        package_added = False
                        
                        for line in lines:
                            new_lines.append(line)
                            if line.startswith('package ') and not package_added:
                                new_lines.append('')
                                new_lines.append(import_stmt)
                                package_added = True
                        
                        content = '\n'.join(new_lines)
                
                # Fix class declaration
                if 'MainAPI' in content and 'class' in content:
                    if not re.search(r'class\s+\w+\s*:\s*MainAPI', content):
                        # Find class declaration and fix it
                        content = re.sub(
                            r'class\s+(\w+)\s*\(\s*\)',
                            r'class \1 : MainAPI()',
                            content
                        )
                
                if content != original_content:
                    with open(provider_path, 'w', encoding='utf-8') as f:
                        f.write(content)
                    print(f"  ✅ تم تحديث {provider_path}")

def main():
    print('🔧 إصلاح المشاكل المتبقية في ملحقات CloudStream العربية')
    print('=' * 60)
    
    fix_manifest_permissions()
    fix_plugin_imports()
    fix_provider_imports()
    
    print(f"\n✅ تم اكتمال الإصلاحات المتبقية!")

if __name__ == '__main__':
    main()