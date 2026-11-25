import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class StripMediaPipeTelemetryFactory :
    AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun isInstrumentable(classData: ClassData): Boolean {
        val name = classData.className
        return name.startsWith("com.google.mediapipe.tasks.core.logging.") &&
            !name.startsWith("com.google.mediapipe.tasks.core.logging.TasksStatsLogger") &&
            !name.startsWith("com.google.mediapipe.tasks.core.logging.TasksStatsDummyLogger")
    }

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        val api = instrumentationContext.apiVersion.get()
        return object : ClassVisitor(api, nextClassVisitor) {
            private var isProtoLogger = false
            private var isInterface = false
            private var superClassName = "java/lang/Object"
            private var hasNoArgCtor = false

            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<String>?
            ) {
                isProtoLogger =
                    (name == "com/google/mediapipe/tasks/core/logging/TasksStatsProtoLogger")
                isInterface = (access and Opcodes.ACC_INTERFACE) != 0
                superClassName = superName ?: "java/lang/Object"
                val cleanSig = signature?.takeUnless {
                    it.contains("datatransport") || it.contains("firebase")
                }
                val cleanIfaces = interfaces
                    ?.filterNot { it.contains("datatransport") || it.contains("firebase") }
                    ?.toTypedArray()
                super.visit(version, access, name, cleanSig, superName, cleanIfaces)
            }

            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?
            ): FieldVisitor? {
                if (descriptor.contains("datatransport") || descriptor.contains("firebase") ||
                    (signature != null &&
                        (signature.contains("datatransport") || signature.contains("firebase")))
                ) {
                    return null
                }
                return super.visitField(access, name, descriptor, signature, value)
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<String>?
            ): MethodVisitor? {
                if (descriptor.contains("datatransport") || descriptor.contains("firebase")) {
                    return null
                }
                if (isInterface || (access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE)) != 0) {
                    return super.visitMethod(access, name, descriptor, null, exceptions)
                }

                val target = super.visitMethod(access, name, descriptor, null, exceptions)
                val maxLocals = (Type.getArgumentsAndReturnSizes(descriptor) shr 2) + 1

                target.visitCode()
                when {
                    name == "<init>" -> {
                        if (descriptor == "()V") hasNoArgCtor = true
                        target.visitVarInsn(Opcodes.ALOAD, 0)
                        target.visitMethodInsn(
                            Opcodes.INVOKESPECIAL,
                            superClassName,
                            "<init>",
                            "()V",
                            false
                        )
                        target.visitInsn(Opcodes.RETURN)
                        target.visitMaxs(1, maxLocals)
                    }
                    isProtoLogger && name == "create" && (access and Opcodes.ACC_STATIC) != 0 -> {
                        target.visitTypeInsn(
                            Opcodes.NEW,
                            "com/google/mediapipe/tasks/core/logging/TasksStatsProtoLogger"
                        )
                        target.visitInsn(Opcodes.DUP)
                        target.visitMethodInsn(
                            Opcodes.INVOKESPECIAL,
                            "com/google/mediapipe/tasks/core/logging/TasksStatsProtoLogger",
                            "<init>",
                            "()V",
                            false
                        )
                        target.visitInsn(Opcodes.ARETURN)
                        target.visitMaxs(2, maxLocals)
                    }
                    else -> {
                        when (Type.getReturnType(descriptor).sort) {
                            Type.VOID -> target.visitInsn(Opcodes.RETURN)
                            Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                                target.visitInsn(Opcodes.ICONST_0)
                                target.visitInsn(Opcodes.IRETURN)
                            }
                            Type.LONG -> {
                                target.visitInsn(Opcodes.LCONST_0)
                                target.visitInsn(Opcodes.LRETURN)
                            }
                            Type.FLOAT -> {
                                target.visitInsn(Opcodes.FCONST_0)
                                target.visitInsn(Opcodes.FRETURN)
                            }
                            Type.DOUBLE -> {
                                target.visitInsn(Opcodes.DCONST_0)
                                target.visitInsn(Opcodes.DRETURN)
                            }
                            else -> {
                                target.visitInsn(Opcodes.ACONST_NULL)
                                target.visitInsn(Opcodes.ARETURN)
                            }
                        }
                        target.visitMaxs(2, maxLocals)
                    }
                }
                target.visitEnd()
                return object : MethodVisitor(api) {}
            }

            override fun visitEnd() {
                if (isProtoLogger && !hasNoArgCtor) {
                    val mv = super.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null)
                    mv.visitCode()
                    mv.visitVarInsn(Opcodes.ALOAD, 0)
                    mv.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        superClassName,
                        "<init>",
                        "()V",
                        false
                    )
                    mv.visitInsn(Opcodes.RETURN)
                    mv.visitMaxs(1, 1)
                    mv.visitEnd()
                }
                super.visitEnd()
            }
        }
    }
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

val envKeystorePath = System.getenv("KEYSTORE_PATH")
val hasEnvKeystore = !envKeystorePath.isNullOrBlank()
val hasReleaseKeystore = keystorePropsFile.exists() || hasEnvKeystore

android {
    namespace = "io.github.system32crowdstrikewin11.openaircursor"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "io.github.system32crowdstrikewin11.openaircursor"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.0.6"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        } else if (hasEnvKeystore) {
            create("release") {
                storeFile = file(envKeystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            } else if (project.hasProperty("signWithDebug")) {
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        noCompress += "task"
    }
}

androidComponents {
    onVariants { variant ->
        variant.instrumentation.transformClassesWith(
            StripMediaPipeTelemetryFactory::class.java,
            InstrumentationScope.ALL
        ) {}
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COPY_FRAMES
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")

    implementation("com.google.mediapipe:tasks-vision:1.2.1") {
        exclude(group = "com.google.android.datatransport")
        exclude(group = "com.google.firebase")
    }

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("top.yukonga.miuix.kmp:miuix:0.3.0")
}
