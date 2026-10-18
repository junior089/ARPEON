plugins {
    base
}

allprojects {
    group = "org.arpeon"
    version = "0.1.2"
    repositories { mavenCentral() }
}

val javaProjects = subprojects.filter { it.path != ":apps" && it.path != ":modules" }
configure(javaProjects) {
    apply(plugin = "java-library")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }
    val javaFormatter = configurations.create("javaFormatter")
    val formatterLauncher = extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    dependencies {
        "javaFormatter"("com.google.googlejavaformat:google-java-format:1.37.0:all-deps") {
            isTransitive = false
        }
        "testImplementation"(platform("org.junit:junit-bom:5.14.1"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
    val sources = fileTree("src") { include("**/*.java") }
    for ((taskName, formatArguments) in mapOf(
        "formatJava" to listOf("--replace"),
        "checkJavaFormat" to listOf("--dry-run", "--set-exit-if-changed")
    )) {
        tasks.register<JavaExec>(taskName) {
            group = "verification"
            classpath = javaFormatter
            mainClass.set("com.google.googlejavaformat.java.Main")
            javaLauncher.set(formatterLauncher)
            // google-java-format uses javac's parser; exports are confined to this tool process.
            jvmArgs(listOf("api", "code", "file", "parser", "tree", "util").map {
                "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED"
            })
            args(formatArguments + sources.files.sorted().map { it.absolutePath })
        }
    }
    tasks.named("check") { dependsOn("checkJavaFormat") }
    dependencyLocking { lockAllConfigurations() }
}

tasks.named("build") { dependsOn(javaProjects.map { "${it.path}:build" }) }
tasks.named("check") { dependsOn(javaProjects.map { "${it.path}:check" }) }
