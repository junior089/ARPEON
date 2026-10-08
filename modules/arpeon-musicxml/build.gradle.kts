plugins { `java-library` }
dependencies { api(project(":modules:arpeon-domain")) }

tasks.test { systemProperty("arpeon.fixtures", rootProject.file("fixtures/musicxml").absolutePath) }
