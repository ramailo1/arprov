#!/usr/bin/env python3
"""
Script to fix all AndroidManifest.xml files in Arabic CloudStream extensions
Converts from bloated manifests to minimal CloudStream extension format
"""

import os
import re
from pathlib import Path

def get_provider_name_from_path(manifest_path):
    """Extract provider name from directory path"""
    path_parts = Path(manifest_path).parts
    for part in path_parts:
        if 'Provider' in part and part != 'src':
            return part.replace('Provider', '').lower()
    return 'unknown'

def fix_manifest(manifest_path):
    """Fix a single AndroidManifest.xml file"""
    try:
        provider_name = get_provider_name_from_path(manifest_path)
        
        # Create minimal manifest content
        minimal_manifest = f'''<?xml version="1.0" encoding="utf-8"?>
<manifest package="com.lagradost.cloudstream3.{provider_name}" />
'''
        
        # Write the fixed manifest
        with open(manifest_path, 'w', encoding='utf-8') as f:
            f.write(minimal_manifest)
        
        print(f"✅ Fixed: {manifest_path}")
        return True
        
    except Exception as e:
        print(f"❌ Error fixing {manifest_path}: {e}")
        return False

def find_all_manifests(base_path):
    """Find all AndroidManifest.xml files in provider directories"""
    manifests = []
    base_path = Path(base_path)
    
    # Look for provider directories
    for item in base_path.iterdir():
        if item.is_dir() and ('Provider' in item.name or item.name == 'Extractors'):
            manifest_path = item / 'src' / 'main' / 'AndroidManifest.xml'
            if manifest_path.exists():
                manifests.append(str(manifest_path))
    
    return manifests

def main():
    """Main function to fix all manifests"""
    print("🔧 Starting AndroidManifest.xml fix for Arabic CloudStream extensions")
    
    # Get current directory
    current_dir = Path.cwd()
    print(f"📁 Working in: {current_dir}")
    
    # Find all manifests
    manifests = find_all_manifests(current_dir)
    print(f"📋 Found {len(manifests)} manifest files to fix")
    
    # Fix each manifest
    fixed_count = 0
    for manifest_path in manifests:
        if fix_manifest(manifest_path):
            fixed_count += 1
    
    print(f"\n🎉 Completed! Fixed {fixed_count}/{len(manifests)} manifest files")
    
    # List the fixed files
    print("\n📄 Fixed manifests:")
    for manifest in manifests:
        provider = get_provider_name_from_path(manifest)
        print(f"  • {provider}: com.lagradost.cloudstream3.{provider}")

if __name__ == "__main__":
    main()