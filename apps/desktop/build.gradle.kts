plugins { application }

dependencies {
    implementation(project(":modules:arpeon-tuner"))
    implementation(project(":modules:arpeon-musicxml"))
    implementation(project(":modules:arpeon-playback"))
    implementation(project(":modules:arpeon-import"))
    implementation(project(":modules:arpeon-omr"))
    implementation(project(":modules:arpeon-analysis"))
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("me.friwi:jcefmaven:152.0.6")
}

val cefNatives = configurations.create("cefNatives")
val cefPlatform = when {
    System.getProperty("os.name").startsWith("Windows") -> "windows-amd64"
    System.getProperty("os.name").startsWith("Mac") -> if (System.getProperty("os.arch") == "aarch64") "macosx-arm64" else "macosx-amd64"
    else -> if (System.getProperty("os.arch") == "aarch64") "linux-arm64" else "linux-amd64"
}
dependencies {
    cefNatives("me.friwi:jcef-natives-$cefPlatform:jcef-17e805a+cef-152.0.6+g708dc14+chromium-152.0.7977.83")
}
tasks.register<JavaExec>("prepareChromium") {
    group = "distribution"
    description = "Extract the pinned Chromium runtime at build time, never on end-user launch."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath + cefNatives
    mainClass.set("org.arpeon.desktop.ChromiumBundle")
    args(layout.buildDirectory.dir("native-resources/cef").get().asFile.absolutePath)
}

application {
    mainClass.set("org.arpeon.desktop.DesktopApplication")
    applicationDefaultJvmArgs = listOf("-Xmx2g", "--enable-native-access=ALL-UNNAMED")
}
tasks.named<JavaExec>("run") {
    val installation = providers.gradleProperty("arpeonInstallDir")
    if (installation.isPresent) systemProperty("arpeon.installDir", installation.get())
    doFirst {
        check(installation.isPresent) {
            "Build a complete app image, then run with -ParpeonInstallDir=<absolute ARPEON directory>. See docs/development/setup.md."
        }
    }
}
tasks.jar { manifest.attributes["Implementation-Version"] = project.version.toString() }
tasks.test {
    systemProperty("arpeon.fixtures", rootProject.file("fixtures/musicxml").absolutePath)
    systemProperty("arpeon.dataDir", layout.buildDirectory.dir("test-profile").get().asFile.absolutePath)
}

val frontendOutput = rootProject.layout.projectDirectory.dir("frontend/dist/arpeon/browser")
val versionMetadata = tasks.register("versionMetadata") {
    val output = layout.buildDirectory.file("version/arpeon-version.properties")
    val versionValue = project.version.toString()
    inputs.property("version", versionValue)
    outputs.file(output)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText("version=$versionValue\n", Charsets.UTF_8)
        }
    }
}
val sourceArchive = tasks.register<Zip>("sourceArchive") {
    archiveFileName.set("source.zip")
    destinationDirectory.set(layout.buildDirectory.dir("source"))
    from(rootProject.projectDir) {
        include("*.md", "LICENSE", "*.kts", "gradle.properties", "gradlew", "gradlew.bat", ".gitignore", ".gitattributes", ".editorconfig")
        include("gradle/**", "docs/**", "fixtures/**", ".github/**", "scripts/**", "packaging/**")
        include("modules/**", "apps/**", "frontend/**")
        exclude("**/build/**", "**/node_modules/**", "**/dist/**", "**/.angular/**", "**/coverage/**")
        exclude {
            val name = it.file.name.lowercase()
            name == ".env" || (name.startsWith(".env.") && name != ".env.example") ||
                listOf(".pem", ".key", ".pfx", ".p12", ".jks", ".keystore").any(name::endsWith)
        }
    }
}
tasks.processResources {
    from(versionMetadata)
    from(frontendOutput) { into("web") }
    from(rootProject.file("LICENSE")) { into("web"); rename { "license.txt" } }
    from(rootProject.file("THIRD_PARTY_NOTICES.md")) { into("web"); rename { "notices.txt" } }
    from(rootProject.file("docs/legal/licenses")) { into("web/licenses") }
    from(rootProject.file("frontend/dist/arpeon/3rdpartylicenses.txt")) { into("web") }
    from(sourceArchive) { into("web") }
    doFirst {
        check(frontendOutput.file("index.html").asFile.isFile) {
            "Build the Angular frontend first: cd frontend && npm ci && npm run build"
        }
    }
}
