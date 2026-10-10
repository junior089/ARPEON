plugins { application }

dependencies { implementation(project(":modules:arpeon-analysis")) }

application { mainClass.set("org.arpeon.ml.FollowerEvaluation") }
