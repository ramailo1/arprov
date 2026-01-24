# 🐳 Dockerfile - CloudStream Extensions Arabic
# هذا الملف يستخدم لإنشاء حاوية Docker للمشروع

# 🎯 المرحلة الأولى: البناء
FROM openjdk:17-jdk-slim as builder

# 📋 تعيين المتغيرات
ENV GRADLE_VERSION=8.5
ENV GRADLE_HOME=/opt/gradle
ENV PATH=$PATH:$GRADLE_HOME/bin

# 📦 تثبيت الأدوات المطلوبة
RUN apt-get update && apt-get install -y \
    wget \
    unzip \
    git \
    python3 \
    python3-pip \
    && rm -rf /var/lib/apt/lists/*

# 🔧 تثبيت Gradle
RUN wget https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip && \
    unzip gradle-${GRADLE_VERSION}-bin.zip -d /opt && \
    ln -s /opt/gradle-${GRADLE_VERSION} /opt/gradle && \
    rm gradle-${GRADLE_VERSION}-bin.zip

# 📁 إعداد دليل العمل
WORKDIR /app

# 📋 نسخ ملفات المشروع
COPY . .

# 🏗️ بناء المشروع
RUN ./gradlew build --no-daemon

# 🎯 المرحلة الثانية: التشغيل
FROM openjdk:17-jre-slim

# 📋 تعيين المتغيرات
ENV APP_NAME=cloudstream-extensions-arabic
ENV APP_VERSION=2.0.0
ENV JAVA_OPTS="-Xmx512m -Xms256m"

# 📦 تثبيت الأدوات المطلوبة
RUN apt-get update && apt-get install -y \
    python3 \
    python3-pip \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 📁 إنشاء مستخدم غير جذري
RUN groupadd -r appuser && useradd -r -g appuser appuser

# 📁 إعداد دليل العمل
WORKDIR /app

# 📋 نسخ الملفات من مرحلة البناء
COPY --from=builder /app/build ./build
COPY --from=builder /app/scripts ./scripts
COPY --from=builder /app/docs ./docs
COPY --from=builder /app/repo.json ./repo.json
COPY --from=builder /app/requirements.txt ./requirements.txt
COPY --from=builder /app/package.json ./package.json
COPY --from=builder /app/Makefile ./Makefile

# 📦 تثبيت المتطلبات
RUN pip3 install -r requirements.txt

# 🔒 تعيين الأذونات
RUN chown -R appuser:appuser /app

# 👤 التبديل إلى المستخدم غير الجذري
USER appuser

# 🌐 تعريف المنفذ
EXPOSE 8080

# 🏃 أمر التشغيل
CMD ["python3", "scripts/serve.py"]

# 🏷️ البيانات الوصفية للحاوية
LABEL maintainer="dhomred <dhomred@github.com>"
LABEL version="2.0.0"
LABEL description="CloudStream Extensions Arabic - امتدادات عربية متقدمة لتطبيق CloudStream"
LABEL org.opencontainers.image.title="CloudStream Extensions Arabic"
LABEL org.opencontainers.image.description="امتدادات عربية متقدمة لتطبيق CloudStream"
LABEL org.opencontainers.image.version="2.0.0"
LABEL org.opencontainers.image.source="https://github.com/dhomred/cloudstream-extensions-arabic-v2"
LABEL org.opencontainers.image.licenses="MIT"
LABEL org.opencontainers.image.vendor="dhomred"

# 📝 ملاحظات استخدام الحاوية
# docker build -t cloudstream-extensions-arabic .
# docker run -p 8080:8080 cloudstream-extensions-arabic
# docker run -d --name csa-extensions -p 8080:8080 cloudstream-extensions-arabic