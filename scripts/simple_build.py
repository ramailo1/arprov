import os
import subprocess
import json

def create_simple_build():
    """Create a simple build script that mimics the build process"""
    print("🔨 إنشاء عملية بناء مبسطة...")
    
    # Create output directory
    os.makedirs("build", exist_ok=True)
    
    # Get all provider directories
    providers = []
    for item in os.listdir('.'):
        if os.path.isdir(item) and item.endswith('Provider') and item != 'Extractors':
            providers.append(item)
    
    print(f"📦 تم العثور على {len(providers)} موفر")
    
    # Create plugins.json
    plugins_data = {
        "plugins": []
    }
    
    successful_builds = 0
    failed_builds = []
    
    for provider in providers:
        try:
            print(f"\n🔧 بناء {provider}...")
            
            # Read build.gradle.kts
            build_file = os.path.join(provider, "build.gradle.kts")
            if not os.path.exists(build_file):
                print(f"  ❌ ملف build.gradle.kts مفقود")
                failed_builds.append(f"{provider}: ملف build.gradle.kts مفقود")
                continue
            
            # Read manifest
            manifest_file = os.path.join(provider, "src", "main", "AndroidManifest.xml")
            if not os.path.exists(manifest_file):
                print(f"  ❌ ملف AndroidManifest.xml مفقود")
                failed_builds.append(f"{provider}: ملف AndroidManifest.xml مفقود")
                continue
            
            # Check for Kotlin files
            kotlin_dir = os.path.join(provider, "src", "main", "kotlin")
            if not os.path.exists(kotlin_dir):
                print(f"  ❌ دليل Kotlin مفقود")
                failed_builds.append(f"{provider}: دليل Kotlin مفقود")
                continue
            
            # Find Plugin.kt
            plugin_files = []
            for root, dirs, files in os.walk(kotlin_dir):
                for file in files:
                    if file.endswith("Plugin.kt"):
                        plugin_files.append(os.path.join(root, file))
            
            if not plugin_files:
                print(f"  ❌ ملف Plugin.kt مفقود")
                failed_builds.append(f"{provider}: ملف Plugin.kt مفقود")
                continue
            
            # Find Provider.kt
            provider_files = []
            for root, dirs, files in os.walk(kotlin_dir):
                for file in files:
                    if file.endswith("Provider.kt") and "Plugin" not in file:
                        provider_files.append(os.path.join(root, file))
            
            if not provider_files:
                print(f"  ❌ ملف Provider.kt مفقود")
                failed_builds.append(f"{provider}: ملف Provider.kt مفقود")
                continue
            
            # Parse build.gradle.kts for metadata
            with open(build_file, 'r', encoding='utf-8') as f:
                build_content = f.read()
            
            # Extract metadata
            metadata = {}
            
            # Extract version
            version_match = re.search(r'version\s*=\s*(\d+)', build_content)
            metadata['version'] = int(version_match.group(1)) if version_match else 1
            
            # Extract description
            description_match = re.search(r'description\s*=\s*"([^"]*)"', build_content)
            metadata['description'] = description_match.group(1) if description_match else ""
            
            # Extract author
            author_match = re.search(r'authors?\s*=\s*"([^"]*)"', build_content)
            metadata['author'] = author_match.group(1) if author_match else "Unknown"
            
            # Extract language
            language_match = re.search(r'language\s*=\s*"([^"]*)"', build_content)
            metadata['language'] = language_match.group(1) if language_match else "en"
            
            # Extract tvTypes
            tvtypes_match = re.search(r'tvTypes\s*=\s*"([^"]*)"', build_content)
            metadata['tvTypes'] = tvtypes_match.group(1) if tvtypes_match else "Others"
            
            # Extract iconUrl
            icon_match = re.search(r'iconUrl\s*=\s*"([^"]*)"', build_content)
            metadata['iconUrl'] = icon_match.group(1) if icon_match else ""
            
            # Extract status
            status_match = re.search(r'status\s*=\s*(\d+)', build_content)
            metadata['status'] = int(status_match.group(1)) if status_match else 1
            
            # Create plugin info
            plugin_info = {
                "name": provider.replace("Provider", ""),
                "version": metadata['version'],
                "description": metadata['description'],
                "author": metadata['author'],
                "language": metadata['language'],
                "tvTypes": metadata['tvTypes'],
                "iconUrl": metadata['iconUrl'],
                "status": metadata['status'],
                "file": f"{provider}.cs3"
            }
            
            plugins_data["plugins"].append(plugin_info)
            
            # Create a simple .cs3 file (zip archive)
            import zipfile
            cs3_file = f"build/{provider}.cs3"
            
            with zipfile.ZipFile(cs3_file, 'w') as zf:
                # Add manifest
                zf.write(manifest_file, "AndroidManifest.xml")
                
                # Add all Kotlin files
                for plugin_file in plugin_files:
                    arcname = os.path.relpath(plugin_file, kotlin_dir)
                    zf.write(plugin_file, arcname)
                
                for provider_file in provider_files:
                    arcname = os.path.relpath(provider_file, kotlin_dir)
                    zf.write(provider_file, arcname)
            
            print(f"  ✅ تم بناء {provider} بنجاح")
            successful_builds += 1
            
        except Exception as e:
            print(f"  ❌ فشل بناء {provider}: {str(e)}")
            failed_builds.append(f"{provider}: {str(e)}")
    
    # Save plugins.json
    with open("build/plugins.json", "w", encoding='utf-8') as f:
        json.dump(plugins_data, f, indent=2, ensure_ascii=False)
    
    print(f"\n📊 ملخص البناء:")
    print(f"  ✅ ناجح: {successful_builds}")
    print(f"  ❌ فاشل: {len(failed_builds)}")
    
    if failed_builds:
        print(f"\n📋 الأخطاء:")
        for error in failed_builds:
            print(f"  - {error}")
    
    print(f"\n📦 تم إنشاء {successful_builds} ملحق في دليل build/")
    print(f"📄 تم إنشاء build/plugins.json")

if __name__ == '__main__':
    import re
    create_simple_build()