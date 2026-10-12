plugins { `java-library` }
dependencies {
    api(project(":modules:arpeon-musicxml"))
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    testImplementation(project(":modules:arpeon-transport"))
}
