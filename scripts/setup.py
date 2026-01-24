#!/usr/bin/env python3
"""
🚀 برنامج إعداد CloudStream Extensions Arabic
هذا البرنامج يساعد في إعداد المشروع للتطوير بشكل سلس وسهل
"""

import os
import sys
import subprocess
import platform
import json
from pathlib import Path
from typing import List, Dict, Optional

# 🎨 الألوان للطباعة الملونة
class Colors:
    HEADER = '\033[95m'
    OKBLUE = '\033[94m'
    OKCYAN = '\033[96m'
    OKGREEN = '\033[92m'
    WARNING = '\033[93m'
    FAIL = '\033[91m'
    ENDC = '\033[0m'
    BOLD = '\033[1m'
    UNDERLINE = '\033[4m'

def print_header(text: str):
    """طباعة عنوان مميز"""
    print(f"\n{Colors.HEADER}{Colors.BOLD}{'='*60}{Colors.ENDC}")
    print(f"{Colors.HEADER}{Colors.BOLD}{text.center(60)}{Colors.ENDC}")
    print(f"{Colors.HEADER}{Colors.BOLD}{'='*60}{Colors.ENDC}\n")

def print_success(text: str):
    """طباعة رسالة نجاح"""
    print(f"{Colors.OKGREEN}✅ {text}{Colors.ENDC}")

def print_warning(text: str):
    """طباعة رسالة تحذير"""
    print(f"{Colors.WARNING}⚠️  {text}{Colors.ENDC}")

def print_error(text: str):
    """طباعة رسالة خطأ"""
    print(f"{Colors.FAIL}❌ {text}{Colors.ENDC}")

def print_info(text: str):
    """طباعة رسالة معلومات"""
    print(f"{Colors.OKBLUE}ℹ️  {text}{Colors.ENDC}")

def run_command(command: List[str], description: str, check: bool = True) -> bool:
    """تشغيل أمر نظامي"""
    print_info(f"جاري {description}...")
    try:
        result = subprocess.run(command, capture_output=True, text=True, check=check)
        if result.returncode == 0:
            print_success(f"تم {description} بنجاح")
            return True
        else:
            print_error(f"فشل {description}: {result.stderr}")
            return False
    except subprocess.CalledProcessError as e:
        print_error(f"فشل {description}: {e}")
        return False
    except FileNotFoundError:
        print_error(f"الأمر غير موجود: {command[0]}")
        return False

def check_python_version() -> bool:
    """التحقق من إصدار Python"""
    print_info("التحقق من إصدار Python...")
    version = sys.version_info
    if version.major >= 3 and version.minor >= 8:
        print_success(f"Python {version.major}.{version.minor}.{version.micro} متوافق")
        return True
    else:
        print_error("Python 3.8+ مطلوب")
        return False

def check_java_version() -> bool:
    """التحقق من إصدار Java"""
    print_info("التحقق من إصدار Java...")
    try:
        result = subprocess.run(["java", "-version"], capture_output=True, text=True)
        if result.returncode == 0:
            print_success("Java مثبت ومتوفر")
            return True
        else:
            print_warning("Java غير مثبت أو غير متاح")
            return False
    except FileNotFoundError:
        print_warning("Java غير مثبت")
        return False

def install_python_dependencies() -> bool:
    """تثبيت المتطلبات Python"""
    print_info("تثبيت متطلبات Python...")
    
    # التحقق من وجود ملف requirements.txt
    requirements_file = Path("requirements.txt")
    if not requirements_file.exists():
        print_warning("ملف requirements.txt غير موجود، سيتم إنشاؤه...")
        create_requirements_file()
    
    # ترقية pip
    if not run_command([sys.executable, "-m", "pip", "install", "--upgrade", "pip"], 
                      "ترقية pip"):
        return False
    
    # تثبيت المتطلبات
    return run_command([sys.executable, "-m", "pip", "install", "-r", "requirements.txt"], 
                      "تثبيت متطلبات Python")

def create_requirements_file():
    """إنشاء ملف requirements.txt إذا لم يكن موجودًا"""
    requirements = [
        "requests>=2.28.0",
        "beautifulsoup4>=4.11.0",
        "lxml>=4.9.0",
        "selenium>=4.0.0",
        "pydantic>=1.10.0",
        "python-dotenv>=0.19.0",
        "colorama>=0.4.0",
        "tqdm>=4.64.0",
        "pyyaml>=6.0",
        "jsonschema>=4.0.0"
    ]
    
    with open("requirements.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(requirements))
    
    print_success("تم إنشاء ملف requirements.txt")

def setup_gradle() -> bool:
    """إعداد Gradle"""
    print_info("إعداد Gradle...")
    
    # التحقق من وجود ملف gradlew
    gradlew_file = Path("gradlew")
    if not gradlew_file.exists():
        print_warning("ملف gradlew غير موجود")
        return False
    
    # جعل gradlew قابلاً للتنفيذ (في Linux/Mac)
    if platform.system() != "Windows":
        os.chmod("gradlew", 0o755)
    
    # بناء المشروع
    return run_command(["./gradlew", "build", "--no-daemon"], "بناء المشروع باستخدام Gradle")

def create_directories() -> bool:
    """إنشاء المجلدات المطلوبة"""
    print_info("إنشاء المجلدات المطلوبة...")
    
    directories = [
        "logs",
        "data", 
        "backups",
        "temp",
        "reports",
        "tests/results",
        "scripts/output"
    ]
    
    for directory in directories:
        Path(directory).mkdir(parents=True, exist_ok=True)
        print_success(f"تم إنشاء المجلد: {directory}")
    
    return True

def create_config_files():
    """إنشاء ملفات التكوين"""
    print_info("إنشاء ملفات التكوين...")
    
    # ملف .env
    env_file = Path(".env")
    if not env_file.exists():
        env_content = """# 🎯 إعدادات CloudStream Extensions Arabic
APP_NAME=cloudstream-extensions-arabic
APP_VERSION=2.0.0
DEBUG=false
LOG_LEVEL=INFO

# 🔒 إعدادات الأمان
SECRET_KEY=your-secret-key-here
API_KEY=your-api-key-here

# 🌐 إعدادات الشبكة
PORT=8080
HOST=0.0.0.0
TIMEOUT=30

# 📊 إعدادات التحليلات
ENABLE_ANALYTICS=true
ANALYTICS_ENDPOINT=https://analytics.example.com

# 🗄️ إعدادات قاعدة البيانات
DATABASE_URL=sqlite:///data/extensions.db
REDIS_URL=redis://localhost:6379

# 📧 إعدادات البريد الإلكتروني
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USER=your-email@gmail.com
SMTP_PASS=your-password
"""
        with open(".env", "w", encoding="utf-8") as f:
            f.write(env_content)
        print_success("تم إنشاء ملف .env")

def validate_setup() -> bool:
    """التحقق من نجاح الإعداد"""
    print_info("التحقق من نجاح الإعداد...")
    
    # قائمة بالملفات والمجلدات المطلوبة
    required_items = [
        ("build.gradle.kts", "ملف بناء Gradle"),
        ("settings.gradle.kts", "إعدادات Gradle"),
        ("repo.json", "ملف الإعدادات"),
        ("requirements.txt", "متطلبات Python"),
        ("scripts", "مجلد السكربتات"),
        ("docs", "مجلد التوثيق"),
        ("gradlew", "سكربت Gradle")
    ]
    
    all_good = True
    for item, description in required_items:
        path = Path(item)
        if path.exists():
            print_success(f"✅ {description} موجود")
        else:
            print_error(f"❌ {description} مفقود")
            all_good = False
    
    return all_good

def print_final_report():
    """طباعة تقرير نهائي"""
    print_header("📋 تقرير الإعداد النهائي")
    
    print_success("🎉 تم إعداد المشروع بنجاح!")
    print_info("\n📚 الخطوات التالية:")
    print("1. 🔍 راجع ملف .env واضبط الإعدادات حسب احتياجاتك")
    print("2. 🚀 استخدم 'make help' لعرض قائمة الأوامر المتاحة")
    print("3. 🏗️ استخدم 'make build' لبناء المشروع")
    print("4. 🧪 استخدم 'make test' لتشغيل الاختبارات")
    print("5. 📖 اقرأ ملف README.md للحصول على مزيد من المعلومات")
    
    print_info("\n🔗 الروابط المفيدة:")
    print("- 📖 README.md: دليل المشروع")
    print("- 🔧 Makefile: أوامر التطوير")
    print("- 📚 docs/: ملفات التوثيق")
    print("- 🐙 GitHub: https://github.com/dhomred/cloudstream-extensions-arabic-v2")
    
    print_info("\n🎊 استمتع بالبرمجة!")

def main():
    """الدالة الرئيسية"""
    print_header("🚀 CloudStream Extensions Arabic - برنامج الإعداد")
    
    print_info("بدء عملية الإعداد...")
    
    # الخطوات الأساسية
    steps = [
        ("التحقق من Python", check_python_version),
        ("التحقق من Java", check_java_version),
        ("تثبيت متطلبات Python", install_python_dependencies),
        ("إعداد Gradle", setup_gradle),
        ("إنشاء المجلدات", create_directories),
        ("إنشاء ملفات التكوين", create_config_files),
        ("التحقق من الإعداد", validate_setup)
    ]
    
    success_count = 0
    total_steps = len(steps)
    
    for step_name, step_function in steps:
        print_info(f"\n📋 الخطوة {success_count + 1}/{total_steps}: {step_name}")
        if step_function():
            success_count += 1
        else:
            print_warning(f"تخطي الخطوة: {step_name}")
    
    # التقرير النهائي
    print_final_report()
    
    if success_count == total_steps:
        print_success(f"\n🎉 تم إعداد {success_count}/{total_steps} خطوات بنجاح!")
        return 0
    else:
        print_warning(f"\n⚠️  تم إعداد {success_count}/{total_steps} خطوات")
        print_info("بعض الخطوات قد فشلت، لكن يمكنك المتابعة والإصلاح لاحقًا")
        return 1

if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print_error("\nتم إيقاف البرنامج بواسطة المستخدم")
        sys.exit(1)
    except Exception as e:
        print_error(f"حدث خطأ غير متوقع: {e}")
        sys.exit(1)