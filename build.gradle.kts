import com.lagradost.cloudstream3.gradle.CloudstreamExtension 
import com.android.build.gradle.BaseExtension

buildscript {
    repositories {
        google()
        mavenCentral()
        // Shitpack repo which contains our tools and dependencies
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        // Cloudstream gradle plugin which makes everything work and builds plugins
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // when running through github workflow, GITHUB_REPOSITORY should contain current repository name
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "user/repo")
    }

    android {
        compileSdkVersion(35)

        defaultConfig {
            minSdk = 21
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
            kotlinOptions {
                jvmTarget = "17" // Required
                allWarningsAsErrors = false
                // Disables some unnecessary features
                freeCompilerArgs = freeCompilerArgs +
                        "-Xno-call-assertions" +
                        "-Xno-param-assertions" +
                        "-Xno-receiver-assertions" +
                        "-Xskip-metadata-version-check"
            }
        }
    }

    dependencies {
        val apk by configurations
        val implementation by configurations

        // Stubs for all Cloudstream classes
        apk("com.github.recloudstream:cloudstream:master-SNAPSHOT")

        // these dependencies can include any of those which are added by the app,
        // but you dont need to include any of them if you dont need them
        // https://github.com/recloudstream/cloudstream/blob/master/app/build.gradle
        implementation(kotlin("stdlib")) // adds standard kotlin features, like listOf, mapOf etc
        implementation("com.github.Blatzar:NiceHttp:0.4.4") // http library
        implementation("org.jsoup:jsoup:1.13.1") // html parser
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.+")
		implementation("org.mozilla:rhino:1.7.14")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    }
}

task<Delete>("clean") {
    delete(rootProject.buildDir)
}
