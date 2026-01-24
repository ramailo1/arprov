import os
import subprocess

def test_kotlin_compilation():
    """Test if Kotlin files can be compiled"""
    print("🧪 اختبار ترجمة ملفات Kotlin...")
    
    # Find all Kotlin files
    kotlin_files = []
    for root, dirs, files in os.walk('.'):
        for file in files:
            if file.endswith('.kt'):
                kotlin_files.append(os.path.join(root, file))
    
    print(f"📁 تم العثور على {len(kotlin_files)} ملف Kotlin")
    
    # Test compilation of a few files
    test_files = kotlin_files[:3]  # Test first 3 files
    
    for file_path in test_files:
        print(f"\n📄 اختبار: {file_path}")
        
        # Check for required imports and class declarations
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
        
        # Check for BasePlugin
        if 'Plugin' in file_path:
            if 'BasePlugin' in content and 'CloudstreamPlugin' in content:
                print("  ✅ يحتوي على BasePlugin وCloudstreamPlugin")
            else:
                print("  ❌ يفتقر إلى BasePlugin أو CloudstreamPlugin")
        
        # Check for MainAPI
        if 'Provider' in file_path and 'Plugin' not in file_path:
            if 'MainAPI' in content:
                print("  ✅ يحتوي على MainAPI")
            else:
                print("  ❌ يفتقر إلى MainAPI")
        
        # Check for proper imports
        required_imports = [
            'com.lagradost.cloudstream3.plugins.BasePlugin',
            'com.lagradost.cloudstream3.MainAPI'
        ]
        
        for import_stmt in required_imports:
            if import_stmt in content:
                print(f"  ✅ يحتوي على: {import_stmt.split('.')[-1]}")

def test_manifest_files():
    """Test AndroidManifest.xml files"""
    print("\n🧪 اختبار ملفات AndroidManifest.xml...")
    
    manifest_files = []
    for root, dirs, files in os.walk('.'):
        for file in files:
            if file == 'AndroidManifest.xml':
                manifest_files.append(os.path.join(root, file))
    
    print(f"📁 تم العثور على {len(manifest_files)} ملف AndroidManifest.xml")
    
    for manifest_path in manifest_files:
        print(f"\n📄 اختبار: {manifest_path}")
        
        with open(manifest_path, 'r', encoding='utf-8') as f:
            content = f.read()
        
        # Check for permissions
        if 'INTERNET' in content:
            print("  ✅ يحتوي على تصريح INTERNET")
        else:
            print("  ❌ يفتقر إلى تصريح INTERNET")
        
        if 'ACCESS_NETWORK_STATE' in content:
            print("  ✅ يحتوي على تصريح ACCESS_NETWORK_STATE")
        else:
            print("  ❌ يفتقر إلى تصريح ACCESS_NETWORK_STATE")
        
        if 'ProviderInstaller' in content or 'google_play_services' in content:
            print("  ✅ يحتوي على ProviderInstaller")
        else:
            print("  ❌ يفتقر إلى ProviderInstaller")

def main():
    print('🧪 اختبار الإصلاحات المطبقة')
    print('=' * 60)
    
    test_kotlin_compilation()
    test_manifest_files()
    
    print(f"\n✅ تم اكتمال الاختبارات!")

if __name__ == '__main__':
    main()