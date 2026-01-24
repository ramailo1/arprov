import os
import re
import glob

def analyze_provider(provider_path):
    issues = []
    provider_name = os.path.basename(provider_path)
    
    print(f"\n📺 تحليل {provider_name}...")
    
    # Check build.gradle.kts
    build_file = os.path.join(provider_path, 'build.gradle.kts')
    if os.path.exists(build_file):
        with open(build_file, 'r', encoding='utf-8') as f:
            content = f.read()
            if 'version' not in content:
                issues.append('Missing version in build.gradle.kts')
            if 'language' not in content:
                issues.append('Missing language specification')
            if 'tvTypes' not in content:
                issues.append('Missing tvTypes specification')
            if 'authors' not in content:
                issues.append('Missing authors specification')
            if 'status' not in content:
                issues.append('Missing status specification')
    else:
        issues.append('Missing build.gradle.kts')
    
    # Check AndroidManifest.xml
    manifest_file = os.path.join(provider_path, 'src', 'main', 'AndroidManifest.xml')
    if os.path.exists(manifest_file):
        with open(manifest_file, 'r', encoding='utf-8') as f:
            content = f.read()
            if 'INTERNET' not in content:
                issues.append('Missing INTERNET permission')
            if 'ACCESS_NETWORK_STATE' not in content:
                issues.append('Missing ACCESS_NETWORK_STATE permission')
            if 'ProviderInstaller' not in content:
                issues.append('Missing ProviderInstaller declaration')
    else:
        issues.append('Missing AndroidManifest.xml')
    
    # Check Kotlin files
    kotlin_dir = os.path.join(provider_path, 'src', 'main', 'kotlin')
    if os.path.exists(kotlin_dir):
        kt_files = glob.glob(os.path.join(kotlin_dir, '**', '*.kt'), recursive=True)
        has_plugin = False
        has_provider = False
        
        for kt_file in kt_files:
            with open(kt_file, 'r', encoding='utf-8') as f:
                content = f.read()
                
                if 'Plugin' in content and '@CloudstreamPlugin' in content:
                    has_plugin = True
                    
                    # Check plugin extends correct class
                    if 'BasePlugin' not in content and 'Plugin' not in content:
                        issues.append(f'Plugin class in {os.path.basename(kt_file)} does not extend proper base class')
                
                if 'MainAPI' in content:
                    has_provider = True
                    
                    # Check for proper imports
                    if 'import com.lagradost.cloudstream3.MainAPI' not in content:
                        issues.append(f'Missing MainAPI import in {os.path.basename(kt_file)}')
                    
                    # Check for proper class declaration
                    if not re.search(r'class\s+\w+\s*:\s*MainAPI', content):
                        issues.append(f'Improper MainAPI class declaration in {os.path.basename(kt_file)}')
        
        if not has_plugin:
            issues.append('Missing Plugin class')
        if not has_provider:
            issues.append('Missing MainAPI Provider class')
    else:
        issues.append('Missing Kotlin source directory')
    
    return issues

def compare_with_extensions_master():
    """Compare with extensions-master structure"""
    print("\n🔍 مقارنة مع extensions-master...")
    
    # Key differences found
    differences = [
        "1. استخدام Plugin() بدلاً من BasePlugin() في بعض الملحقات",
        "2. اختلاف في إصدار Android SDK (30 vs 35)",
        "3. اختلاف في بنية الحزم (com.* vs recloudstream)",
        "4. بعض الملحقات تفتقر إلى التصاريح الكاملة في AndroidManifest.xml"
    ]
    
    return differences

# Main analysis
print('🔍 تحليل شامل لمشاكل ملحقات CloudStream العربية')
print('=' * 60)

providers_dir = '.'
all_issues = {}
total_providers = 0

# Find all provider directories
for item in os.listdir(providers_dir):
    item_path = os.path.join(providers_dir, item)
    if os.path.isdir(item_path) and item.endswith('Provider') and not item.startswith('.'):
        total_providers += 1
        issues = analyze_provider(item_path)
        if issues:
            all_issues[item] = issues

# Print results
print(f'\n📊 نتائج التحليل:')
print(f'إجمالي المزودين: {total_providers}')
print(f'المزودين ذوي المشاكل: {len(all_issues)}')

if all_issues:
    print(f'\n❌ المشاكل المكتشفة:')
    for provider, issues in all_issues.items():
        print(f'\n📺 {provider}:')
        for issue in issues:
            print(f'  🔴 {issue}')
else:
    print('\n✅ لا توجد مشاكل مكتشفة!')

# Compare with extensions-master
differences = compare_with_extensions_master()
print(f'\n🔄 الفروقات الرئيسية مع extensions-master:')
for diff in differences:
    print(f'  📌 {diff}')

# Summary and recommendations
print(f'\n💡 التوصيات:')
print("1. توحيد بنية الـ Plugin لتستخدم BasePlugin() مثل extensions-master")
print("2. تحديث Android SDK إلى الإصدار 35 لمطابقة extensions-master")
print("3. مراجعة جميع AndroidManifest.xml وإضافة التصاريح المفقودة")
print("4. توحيد بنية الحزم لتتبع نمط recloudstream")
print("5. إضافة فحوصات جودة تلقائية للتأكد من اكتمال جميع الملفات المطلوبة")