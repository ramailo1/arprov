#!/usr/bin/env python3
"""
📊 مولد إحصائيات CloudStream Extensions Arabic
هذا البرنامج يولد إحصائيات شاملة عن المشروع
"""

import os
import json
import glob
import re
from pathlib import Path
from datetime import datetime
from typing import Dict, List, Any
import yaml

class ProjectStats:
    def __init__(self):
        self.root_dir = Path(".")
        self.stats = {
            "project_info": {},
            "files": {},
            "code": {},
            "providers": {},
            "extractors": {},
            "languages": {},
            "quality": {},
            "activity": {},
            "timestamp": datetime.now().isoformat()
        }
    
    def analyze_project_info(self):
        """تحليل معلومات المشروع الأساسية"""
        print("📋 تحليل معلومات المشروع...")
        
        # قراءة repo.json
        repo_file = self.root_dir / "repo.json"
        if repo_file.exists():
            with open(repo_file, "r", encoding="utf-8") as f:
                repo_data = json.load(f)
                self.stats["project_info"] = {
                    "name": repo_data.get("name", "CloudStream Extensions Arabic"),
                    "version": repo_data.get("version", "2.0.0"),
                    "description": repo_data.get("description", ""),
                    "author": repo_data.get("author", "dhomred"),
                    "license": repo_data.get("license", "MIT"),
                    "homepage": repo_data.get("homepage", ""),
                    "repository": repo_data.get("repository", {}).get("url", "")
                }
        
        # قراءة package.json
        package_file = self.root_dir / "package.json"
        if package_file.exists():
            with open(package_file, "r", encoding="utf-8") as f:
                package_data = json.load(f)
                self.stats["project_info"].update({
                    "keywords": package_data.get("keywords", []),
                    "dependencies": len(package_data.get("dependencies", {})),
                    "dev_dependencies": len(package_data.get("devDependencies", {}))
                })
    
    def analyze_files(self):
        """تحليل ملفات المشروع"""
        print("📁 تحليل ملفات المشروع...")
        
        file_types = {}
        total_size = 0
        total_files = 0
        
        # أنواع الملفات المهمة
        important_extensions = [
            ".kt", ".java", ".py", ".js", ".ts", ".json", ".md", ".yml", ".yaml",
            ".gradle", ".properties", ".xml", ".html", ".css", ".scss", ".sass"
        ]
        
        for ext in important_extensions:
            files = list(self.root_dir.rglob(f"*{ext}"))
            if files:
                count = len(files)
                size = sum(f.stat().st_size for f in files if f.is_file())
                
                file_types[ext] = {
                    "count": count,
                    "size_bytes": size,
                    "size_kb": round(size / 1024, 2),
                    "size_mb": round(size / (1024 * 1024), 2)
                }
                
                total_files += count
                total_size += size
        
        self.stats["files"] = {
            "total_files": total_files,
            "total_size_bytes": total_size,
            "total_size_kb": round(total_size / 1024, 2),
            "total_size_mb": round(total_size / (1024 * 1024), 2),
            "file_types": file_types,
            "largest_files": self.get_largest_files(10)
        }
    
    def get_largest_files(self, limit: int = 10) -> List[Dict[str, Any]]:
        """الحصول على أكبر الملفات"""
        files_with_sizes = []
        
        for file_path in self.root_dir.rglob("*"):
            if file_path.is_file() and not str(file_path).startswith("."):
                try:
                    size = file_path.stat().st_size
                    files_with_sizes.append({
                        "path": str(file_path),
                        "size_bytes": size,
                        "size_kb": round(size / 1024, 2),
                        "size_mb": round(size / (1024 * 1024), 2)
                    })
                except (OSError, PermissionError):
                    continue
        
        # ترتيب حسب الحجم
        files_with_sizes.sort(key=lambda x: x["size_bytes"], reverse=True)
        return files_with_sizes[:limit]
    
    def analyze_code(self):
        """تحليل الكود المصدري"""
        print("💻 تحليل الكود المصدري...")
        
        code_stats = {
            "total_lines": 0,
            "total_files": 0,
            "languages": {},
            "code_quality": {}
        }
        
        # تحليل ملفات Kotlin
        kt_files = list(self.root_dir.rglob("*.kt"))
        if kt_files:
            kt_stats = self.analyze_kotlin_files(kt_files)
            code_stats["languages"]["kotlin"] = kt_stats
        
        # تحليل ملفات Python
        py_files = list(self.root_dir.rglob("*.py"))
        if py_files:
            py_stats = self.analyze_python_files(py_files)
            code_stats["languages"]["python"] = py_stats
        
        # تحليل ملفات Java
        java_files = list(self.root_dir.rglob("*.java"))
        if java_files:
            java_stats = self.analyze_java_files(java_files)
            code_stats["languages"]["java"] = java_stats
        
        # حساب الإجمالي
        for lang_stats in code_stats["languages"].values():
            code_stats["total_lines"] += lang_stats.get("total_lines", 0)
            code_stats["total_files"] += lang_stats.get("total_files", 0)
        
        self.stats["code"] = code_stats
    
    def analyze_kotlin_files(self, files: List[Path]) -> Dict[str, Any]:
        """تحليل ملفات Kotlin"""
        stats = {
            "total_files": len(files),
            "total_lines": 0,
            "total_classes": 0,
            "total_functions": 0,
            "total_comments": 0,
            "complexity": 0
        }
        
        for file_path in files:
            try:
                with open(file_path, "r", encoding="utf-8") as f:
                    content = f.read()
                    lines = content.split("\n")
                    
                    stats["total_lines"] += len(lines)
                    stats["total_classes"] += len(re.findall(r"\bclass\s+\w+", content))
                    stats["total_functions"] += len(re.findall(r"\bfun\s+\w+", content))
                    stats["total_comments"] += len(re.findall(r"//.*$", content, re.MULTILINE))
                    stats["total_comments"] += len(re.findall(r"/\*.*?\*/", content, re.DOTALL))
                    
            except (UnicodeDecodeError, PermissionError):
                continue
        
        return stats
    
    def analyze_python_files(self, files: List[Path]) -> Dict[str, Any]:
        """تحليل ملفات Python"""
        stats = {
            "total_files": len(files),
            "total_lines": 0,
            "total_classes": 0,
            "total_functions": 0,
            "total_comments": 0,
            "total_imports": 0,
            "complexity": 0
        }
        
        for file_path in files:
            try:
                with open(file_path, "r", encoding="utf-8") as f:
                    content = f.read()
                    lines = content.split("\n")
                    
                    stats["total_lines"] += len(lines)
                    stats["total_classes"] += len(re.findall(r"^class\s+\w+", content, re.MULTILINE))
                    stats["total_functions"] += len(re.findall(r"^def\s+\w+", content, re.MULTILINE))
                    stats["total_comments"] += len(re.findall(r"#.*$", content, re.MULTILINE))
                    stats["total_comments"] += len(re.findall(r'""".*?"""', content, re.DOTALL))
                    stats["total_imports"] += len(re.findall(r"^(import|from)\s+", content, re.MULTILINE))
                    
            except (UnicodeDecodeError, PermissionError):
                continue
        
        return stats
    
    def analyze_java_files(self, files: List[Path]) -> Dict[str, Any]:
        """تحليل ملفات Java"""
        stats = {
            "total_files": len(files),
            "total_lines": 0,
            "total_classes": 0,
            "total_methods": 0,
            "total_comments": 0,
            "complexity": 0
        }
        
        for file_path in files:
            try:
                with open(file_path, "r", encoding="utf-8") as f:
                    content = f.read()
                    lines = content.split("\n")
                    
                    stats["total_lines"] += len(lines)
                    stats["total_classes"] += len(re.findall(r"\bclass\s+\w+", content))
                    stats["total_methods"] += len(re.findall(r"\b(public|private|protected)\s+.*\w+\s*\(", content))
                    stats["total_comments"] += len(re.findall(r"//.*$", content, re.MULTILINE))
                    stats["total_comments"] += len(re.findall(r"/\*.*?\*/", content, re.DOTALL))
                    
            except (UnicodeDecodeError, PermissionError):
                continue
        
        return stats
    
    def analyze_providers(self):
        """تحليل المزودين"""
        print("📺 تحليل المزودين...")
        
        providers = []
        
        # البحث عن ملفات المزودين
        provider_files = list(self.root_dir.rglob("*Provider*.kt")) + \
                        list(self.root_dir.rglob("*Provider*.java")) + \
                        list(self.root_dir.rglob("*provider*.py"))
        
        for file_path in provider_files:
            try:
                with open(file_path, "r", encoding="utf-8") as f:
                    content = f.read()
                    
                    # استخراج معلومات المزود
                    provider_info = {
                        "file": str(file_path),
                        "name": file_path.stem,
                        "type": self.detect_provider_type(content),
                        "features": self.extract_provider_features(content),
                        "domains": self.extract_domains(content)
                    }
                    
                    providers.append(provider_info)
                    
            except (UnicodeDecodeError, PermissionError):
                continue
        
        self.stats["providers"] = {
            "total_count": len(providers),
            "providers": providers,
            "by_type": self.categorize_providers(providers)
        }
    
    def detect_provider_type(self, content: str) -> str:
        """اكتشاف نوع المزود"""
        if "movie" in content.lower() or "film" in content.lower():
            return "movies"
        elif "series" in content.lower() or "tv" in content.lower():
            return "tv_series"
        elif "anime" in content.lower():
            return "anime"
        elif "live" in content.lower():
            return "live_tv"
        else:
            return "general"
    
    def extract_provider_features(self, content: str) -> List[str]:
        """استخراج مميزات المزود"""
        features = []
        
        if "search" in content.lower():
            features.append("search")
        if "quality" in content.lower():
            features.append("quality_selection")
        if "subtitle" in content.lower():
            features.append("subtitles")
        if "dubbed" in content.lower():
            features.append("dubbed")
        if "live" in content.lower():
            features.append("live_streaming")
        
        return features
    
    def extract_domains(self, content: str) -> List[str]:
        """استخراج النطاقات"""
        domains = re.findall(r'["\'](https?://[^"\']+)["\']', content)
        return list(set(domains))
    
    def categorize_providers(self, providers: List[Dict]) -> Dict[str, int]:
        """تصنيف المزودين حسب النوع"""
        categories = {}
        for provider in providers:
            provider_type = provider.get("type", "unknown")
            categories[provider_type] = categories.get(provider_type, 0) + 1
        return categories
    
    def analyze_extractors(self):
        """تحليل المستخرجات"""
        print("🔧 تحليل المستخرجات...")
        
        extractors = []
        
        # البحث عن ملفات المستخرجات
        extractor_files = list(self.root_dir.rglob("*Extractor*.kt")) + \
                         list(self.root_dir.rglob("*Extractor*.java")) + \
                         list(self.root_dir.rglob("*extractor*.py"))
        
        for file_path in extractor_files:
            try:
                with open(file_path, "r", encoding="utf-8") as f:
                    content = f.read()
                    
                    # استخراج معلومات المستخرج
                    extractor_info = {
                        "file": str(file_path),
                        "name": file_path.stem,
                        "type": self.detect_extractor_type(content),
                        "supported_sites": self.extract_supported_sites(content),
                        "features": self.extract_extractor_features(content)
                    }
                    
                    extractors.append(extractor_info)
                    
            except (UnicodeDecodeError, PermissionError):
                continue
        
        self.stats["extractors"] = {
            "total_count": len(extractors),
            "extractors": extractors,
            "by_type": self.categorize_extractors(extractors)
        }
    
    def detect_extractor_type(self, content: str) -> str:
        """اكتشاف نوع المستخرج"""
        if "stream" in content.lower():
            return "streaming"
        elif "download" in content.lower():
            return "download"
        elif "embed" in content.lower():
            return "embed"
        else:
            return "general"
    
    def extract_supported_sites(self, content: str) -> List[str]:
        """استخراج المواقع المدعومة"""
        sites = re.findall(r'["\']([^"\']*\.(com|net|org|io|co)[^"\']*)["\']', content)
        return list(set([site[0] for site in sites]))
    
    def extract_extractor_features(self, content: str) -> List[str]:
        """استخراج مميزات المستخرج"""
        features = []
        
        if "quality" in content.lower():
            features.append("quality_detection")
        if "subtitle" in content.lower():
            features.append("subtitle_support")
        if "multiple" in content.lower():
            features.append("multiple_links")
        if "proxy" in content.lower():
            features.append("proxy_support")
        
        return features
    
    def categorize_extractors(self, extractors: List[Dict]) -> Dict[str, int]:
        """تصنيف المستخرجات حسب النوع"""
        categories = {}
        for extractor in extractors:
            extractor_type = extractor.get("type", "unknown")
            categories[extractor_type] = categories.get(extractor_type, 0) + 1
        return categories
    
    def analyze_quality(self):
        """تحليل جودة المشروع"""
        print("⭐ تحليل جودة المشروع...")
        
        quality_score = 0
        quality_factors = []
        
        # وجود ملفات مهمة
        important_files = [
            ("README.md", "توثيق المشروع"),
            ("LICENSE", "الترخيص"),
            ("CODE_OF_CONDUCT.md", "معايير السلوك"),
            ("CONTRIBUTING.md", "دليل المساهمة"),
            ("SECURITY.md", "سياسة الأمان"),
            (".gitignore", "إعدادات Git"),
            ("package.json", "إعدادات Node.js"),
            ("Makefile", "أوامر البناء"),
            ("Dockerfile", "حاوية Docker"),
            ("docker-compose.yml", "إعدادات Docker Compose")
        ]
        
        for filename, description in important_files:
            if (self.root_dir / filename).exists():
                quality_score += 10
                quality_factors.append(f"✅ {description} موجود")
            else:
                quality_factors.append(f"❌ {description} مفقود")
        
        # وجود مجلدات مهمة
        important_dirs = [
            ("docs", "التوثيق"),
            ("scripts", "السكربتات"),
            ("tests", "الاختبارات"),
            ("reports", "التقارير")
        ]
        
        for dirname, description in important_dirs:
            if (self.root_dir / dirname).exists():
                quality_score += 5
                quality_factors.append(f"✅ مجلد {description} موجود")
            else:
                quality_factors.append(f"❌ مجلد {description} مفقود")
        
        # وجود اختبارات
        test_files = list(self.root_dir.rglob("*test*.py")) + \
                    list(self.root_dir.rglob("*Test*.kt")) + \
                    list(self.root_dir.rglob("*test*.java"))
        
        if test_files:
            quality_score += 15
            quality_factors.append(f"✅ {len(test_files)} ملف اختبار موجود")
        else:
            quality_factors.append("❌ لا توجد ملفات اختبار")
        
        # وجود CI/CD
        github_workflows = list((self.root_dir / ".github" / "workflows").rglob("*.yml")) if (self.root_dir / ".github" / "workflows").exists() else []
        
        if github_workflows:
            quality_score += 10
            quality_factors.append(f"✅ {len(github_workflows)} عملية CI/CD موجودة")
        else:
            quality_factors.append("❌ لا توجد عمليات CI/CD")
        
        self.stats["quality"] = {
            "score": quality_score,
            "max_score": 150,
            "percentage": round((quality_score / 150) * 100, 2),
            "grade": self.get_quality_grade(quality_score),
            "factors": quality_factors
        }
    
    def get_quality_grade(self, score: int) -> str:
        """الحصول على درجة الجودة"""
        if score >= 130:
            return "A+"
        elif score >= 110:
            return "A"
        elif score >= 90:
            return "B+"
        elif score >= 70:
            return "B"
        elif score >= 50:
            return "C+"
        elif score >= 30:
            return "C"
        else:
            return "D"
    
    def generate_report(self):
        """توليد تقرير شامل"""
        print("📝 توليد التقرير النهائي...")
        
        report = {
            "project_summary": {
                "name": self.stats["project_info"].get("name", "CloudStream Extensions Arabic"),
                "version": self.stats["project_info"].get("version", "2.0.0"),
                "total_files": self.stats["files"].get("total_files", 0),
                "total_size_mb": self.stats["files"].get("total_size_mb", 0),
                "quality_score": self.stats["quality"].get("score", 0),
                "quality_percentage": self.stats["quality"].get("percentage", 0),
                "quality_grade": self.stats["quality"].get("grade", "N/A")
            },
            "code_statistics": {
                "total_lines": self.stats["code"].get("total_lines", 0),
                "total_files": self.stats["code"].get("total_files", 0),
                "languages": list(self.stats["code"].get("languages", {}).keys())
            },
            "providers_statistics": {
                "total_providers": self.stats["providers"].get("total_count", 0),
                "by_type": self.stats["providers"].get("by_type", {})
            },
            "extractors_statistics": {
                "total_extractors": self.stats["extractors"].get("total_count", 0),
                "by_type": self.stats["extractors"].get("by_type", {})
            },
            "recommendations": self.generate_recommendations()
        }
        
        return report
    
    def generate_recommendations(self) -> List[str]:
        """توليد توصيات للتحسين"""
        recommendations = []
        
        # توصيات بناءً على الجودة
        quality_score = self.stats["quality"].get("score", 0)
        if quality_score < 100:
            recommendations.append("🎯 تحسين جودة المشروع من خلال إضافة الملفات المفقودة")
        
        # توصيات بناءً على الاختبارات
        test_files = list(self.root_dir.rglob("*test*.py")) + \
                    list(self.root_dir.rglob("*Test*.kt")) + \
                    list(self.root_dir.rglob("*test*.java"))
        if len(test_files) < 5:
            recommendations.append("🧪 زيادة تغطية الاختبارات")
        
        # توصيات بناءً على التوثيق
        if not (self.root_dir / "docs" / "API.md").exists():
            recommendations.append("📚 إضافة توثيق API")
        
        # توصيات بناءً على CI/CD
        github_workflows = list((self.root_dir / ".github" / "workflows").rglob("*.yml")) if (self.root_dir / ".github" / "workflows").exists() else []
        if len(github_workflows) < 3:
            recommendations.append("🔄 إضافة المزيد من عمليات CI/CD")
        
        return recommendations
    
    def save_stats(self):
        """حفظ الإحصائيات"""
        print("💾 حفظ الإحصائيات...")
        
        # حفظ الإحصائيات الكاملة
        stats_file = self.root_dir / "reports" / "project_stats.json"
        stats_file.parent.mkdir(exist_ok=True)
        
        with open(stats_file, "w", encoding="utf-8") as f:
            json.dump(self.stats, f, ensure_ascii=False, indent=2)
        
        # حفظ التقرير
        report = self.generate_report()
        report_file = self.root_dir / "reports" / "project_report.json"
        
        with open(report_file, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        
        # حفظ تقرير Markdown
        markdown_file = self.root_dir / "reports" / "project_stats.md"
        self.save_markdown_report(report, markdown_file)
        
        print_success(f"✅ تم حفظ الإحصائيات في: {stats_file}")
        print_success(f"✅ تم حفظ التقرير في: {report_file}")
        print_success(f"✅ تم حفظ تقرير Markdown في: {markdown_file}")
    
    def save_markdown_report(self, report: Dict, file_path: Path):
        """حفظ تقرير Markdown"""
        markdown_content = f"""# 📊 إحصائيات CloudStream Extensions Arabic

تم إنشاء هذا التقرير في: {datetime.now().strftime("%Y-%m-%d %H:%M:%S")}

## 🎯 ملخص المشروع

| المقياس | القيمة |
|---------|--------|
| الاسم | {report["project_summary"]["name"]} |
| الإصدار | {report["project_summary"]["version"]} |
| عدد الملفات | {report["project_summary"]["total_files"]} |
| حجم المشروع | {report["project_summary"]["total_size_mb"]} MB |
| درجة الجودة | {report["project_summary"]["quality_grade"]} ({report["project_summary"]["quality_percentage"]}%) |

## 💻 إحصائيات الكود

| المقياس | القيمة |
|---------|--------|
| إجمالي سطور الكود | {report["code_statistics"]["total_lines"]:,} |
| إجمالي ملفات الكود | {report["code_statistics"]["total_files"]} |
| اللغات المستخدمة | {', '.join(report["code_statistics"]["languages"])} |

## 📺 إحصائيات المزودين

| المقياس | القيمة |
|---------|--------|
| إجمالي المزودين | {report["providers_statistics"]["total_providers"]} |
| التصنيفات | {', '.join(report["providers_statistics"]["by_type"].keys())} |

## 🔧 إحصائيات المستخرجات

| المقياس | القيمة |
|---------|--------|
| إجمالي المستخرجات | {report["extractors_statistics"]["total_extractors"]} |
| التصنيفات | {', '.join(report["extractors_statistics"]["by_type"].keys())} |

## 📈 التوصيات

{chr(10).join(f"- {rec}" for rec in report["recommendations"])}

---
*تم إنشاء هذا التقرير تلقائيًا بواسطة نظام إحصائيات CloudStream Extensions Arabic*
"""
        
        with open(file_path, "w", encoding="utf-8") as f:
            f.write(markdown_content)

def main():
    """الدالة الرئيسية"""
    print("🚀 بدء تحليل إحصائيات CloudStream Extensions Arabic...")
    
    stats_generator = ProjectStats()
    
    # تشغيل التحليلات
    stats_generator.analyze_project_info()
    stats_generator.analyze_files()
    stats_generator.analyze_code()
    stats_generator.analyze_providers()
    stats_generator.analyze_extractors()
    stats_generator.analyze_quality()
    
    # حفظ النتائج
    stats_generator.save_stats()
    
    # طباعة ملخص
    report = stats_generator.generate_report()
    
    print("\n" + "="*60)
    print("🎉 تم إكمال تحليل الإحصائيات بنجاح!")
    print(f"📊 درجة الجودة: {report['project_summary']['quality_grade']} ({report['project_summary']['quality_percentage']}%)")
    print(f"📁 إجمالي الملفات: {report['project_summary']['total_files']}")
    print(f"💻 إجمالي سطور الكود: {report['code_statistics']['total_lines']:,}")
    print(f"📺 المزودين: {report['providers_statistics']['total_providers']}")
    print(f"🔧 المستخرجات: {report['extractors_statistics']['total_extractors']}")
    print("="*60)

if __name__ == "__main__":
    main()