import os
import glob
import shutil

def fix_android_manifest(provider_path):
    """Fix AndroidManifest.xml by adding missing permissions and ProviderInstaller"""
    manifest_path = os.path.join(provider_path, 'src', 'main', 'AndroidManifest.xml')
    
    if not os.path.exists(manifest_path):
        # Create new manifest if it doesn't exist
        manifest_content = '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.lagradost.cloudstream3">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application>
        <activity
            android:name="com.lagradost.cloudstream3.MainActivity"
            android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|navigation|keyboardHidden|keyboard|uiMode"
            android:exported="true"
            android:hardwareAccelerated="true"
            android:launchMode="singleTask"
            android:resizeableActivity="true"
            android:supportsPictureInPicture="true"
            android:windowSoftInputMode="adjustPan">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="https" />
                <data android:scheme="http" />
            </intent-filter>
        </activity>

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

        <meta-data
            android:name="android.app.lib_name"
            android:value="native-lib" />
    </application>

</manifest>'''
        
        os.makedirs(os.path.dirname(manifest_path), exist_ok=True)
        with open(manifest_path, 'w', encoding='utf-8') as f:
            f.write(manifest_content)
        print(f"  ✅ تم إنشاء AndroidManifest.xml جديد")
        return
    
    # Read existing manifest
    with open(manifest_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Add missing permissions
    if 'android.permission.INTERNET' not in content:
        content = content.replace(
            '<manifest',
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    package="com.lagradost.cloudstream3">\n\n    <uses-permission android:name="android.permission.INTERNET" />'
        )
    
    if 'android.permission.ACCESS_NETWORK_STATE' not in content:
        content = content.replace(
            '</manifest>',
            '    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />\n\n</manifest>'
        )
    
    # Add ProviderInstaller if missing
    if 'ProviderInstaller' not in content:
        # Simple approach - add before closing application tag
        if '</application>' in content:
            content = content.replace(
                '</application>',
                '        <meta-data\n            android:name="android.app.lib_name"\n            android:value="native-lib" />\n\n</application>'
            )
    
    # Write back the fixed content
    with open(manifest_path, 'w', encoding='utf-8') as f:
        f.write(content)
    
    print(f"  ✅ تم تصحيح AndroidManifest.xml")

def fix_plugin_files(provider_path):
    """Fix Plugin.kt files to use proper BasePlugin and imports"""
    kotlin_dir = os.path.join(provider_path, 'src', 'main', 'kotlin')
    
    if not os.path.exists(kotlin_dir):
        return
    
    # Find all Plugin files
    plugin_files = []
    for root, dirs, files in os.walk(kotlin_dir):
        for file in files:
            if file.endswith('Plugin.kt'):
                plugin_files.append(os.path.join(root, file))
    
    for plugin_file in plugin_files:
        with open(plugin_file, 'r', encoding='utf-8') as f:
            content = f.read()
        
        # Fix imports
        if 'import com.lagradost.cloudstream3.plugins.BasePlugin' not in content:
            # Add proper imports at the beginning
            import_section = '''import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import android.content.Context'''
            
            # Find the package declaration
            lines = content.split('\n')
            new_lines = []
            package_added = False
            
            for line in lines:
                new_lines.append(line)
                if line.startswith('package ') and not package_added:
                    new_lines.append('')
                    new_lines.extend(import_section.split('\n'))
                    package_added = True
            
            content = '\n'.join(new_lines)
        
        # Fix class declaration to use BasePlugin
        if 'class' in content and 'Plugin' in content:
            content = content.replace('Plugin()', 'BasePlugin()')
            content = content.replace('override fun load(context: Context)', 'override fun load()')
        
        # Write back the fixed content
        with open(plugin_file, 'w', encoding='utf-8') as f:
            f.write(content)
        
        print(f"  ✅ تم تصحيح {os.path.basename(plugin_file)}")

def fix_provider_files(provider_path):
    """Fix Provider.kt files to ensure proper MainAPI imports and declarations"""
    kotlin_dir = os.path.join(provider_path, 'src', 'main', 'kotlin')
    
    if not os.path.exists(kotlin_dir):
        return
    
    # Find all Provider files
    provider_files = []
    for root, dirs, files in os.walk(kotlin_dir):
        for file in files:
            if file.endswith('Provider.kt') and 'Plugin' not in file:
                provider_files.append(os.path.join(root, file))
    
    for provider_file in provider_files:
        with open(provider_file, 'r', encoding='utf-8') as f:
            content = f.read()
        
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
            if import_stmt.split()[-1] in content and import_stmt not in content:
                # Add the import after package declaration
                lines = content.split('\n')
                new_lines = []
                package_found = False
                
                for line in lines:
                    new_lines.append(line)
                    if line.startswith('package ') and not package_found:
                        new_lines.append('')
                        new_lines.append(import_stmt)
                        package_found = True
                
                content = '\n'.join(new_lines)
        
        # Ensure proper class declaration
        if 'class' in content and 'MainAPI' in content:
            # Make sure class extends MainAPI properly
            if not ' : MainAPI()' in content and not ': MainAPI()' in content:
                content = content.replace('MainAPI', 'MainAPI()')
        
        # Write back the fixed content
        with open(provider_file, 'w', encoding='utf-8') as f:
            f.write(content)
        
        print(f"  ✅ تم تصحيح {os.path.basename(provider_file)}")

def fix_build_gradle_kts():
    """Fix the main build.gradle.kts to match extensions-master SDK version"""
    build_file = 'build.gradle.kts'
    
    if not os.path.exists(build_file):
        return
    
    with open(build_file, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Update SDK versions to match extensions-master
    content = content.replace('compileSdkVersion(30)', 'compileSdkVersion(35)')
    content = content.replace('targetSdk = 30', 'targetSdk = 35')
    
    # Add missing dependencies if not present
    if 'jackson-module-kotlin' not in content:
        content = content.replace(
            'implementation("org.jsoup:jsoup:1.13.1") // html parser',
            'implementation("org.jsoup:jsoup:1.13.1") // html parser\n        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.+")'
        )
    
    with open(build_file, 'w', encoding='utf-8') as f:
        f.write(content)
    
    print(f"✅ تم تحديث build.gradle.kts")

def main():
    print('🔧 بدء إصلاح جميع مشاكل ملحقات CloudStream العربية')
    print('=' * 60)
    
    providers_dir = '.'
    total_fixed = 0
    
    # Find all provider directories
    for item in os.listdir(providers_dir):
        item_path = os.path.join(providers_dir, item)
        if os.path.isdir(item_path) and item.endswith('Provider') and not item.startswith('.'):
            print(f"\n📺 معالجة {item}...")
            
            try:
                fix_android_manifest(item_path)
                fix_plugin_files(item_path)
                fix_provider_files(item_path)
                total_fixed += 1
                print(f"  ✅ تم إصلاح {item}")
            except Exception as e:
                print(f"  ❌ فشل إصلاح {item}: {str(e)}")
    
    # Fix main build file
    fix_build_gradle_kts()
    
    print(f"\n📊 الإحصائيات النهائية:")
    print(f"تم إصلاح {total_fixed} مزود")
    print(f"✅ تم اكتمال عملية الإصلاح!")

if __name__ == '__main__':
    main()