plugins { application }

dependencies {
    implementation(project(":modules:arpeon-tuner"))
    implementation(project(":modules:arpeon-musicxml"))
    implementation(project(":modules:arpeon-playback"))
    implementation(project(":modules:arpeon-import"))
    implementation(project(":modules:arpeon-omr"))
    implementation(project(":modules:arpeon-analysis"))
    implementation("com.google.code.gson:gson:2.14.0")
}

application {
    mainClass.set("org.arpeon.desktop.DesktopApplication")
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}
tasks.test { systemProperty("arpeon.fixtures", rootProject.file("fixtures/musicxml").absolutePath) }

val frontendOutput = rootProject.layout.projectDirectory.dir("frontend/dist/arpeon/browser")
val sourceArchive = tasks.register<Zip>("sourceArchive") {
    archiveFileName.set("source.zip")
    destinationDirectory.set(layout.buildDirectory.dir("source"))
    from(rootProject.projectDir) {
        include("*.md", "LICENSE", "*.kts", "gradle.properties", "gradlew", "gradlew.bat", ".gitignore", ".gitattributes", ".editorconfig")
        include("gradle/**", "docs/**", "fixtures/**", ".github/**", "scripts/**")
        include("modules/**", "apps/**", "frontend/**")
        exclude("**/build/**", "**/node_modules/**", "**/dist/**", "**/.angular/**", "**/coverage/**")
    }
}
tasks.processResources {
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
