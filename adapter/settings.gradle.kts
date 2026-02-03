pluginManagement {
    repositories {
        mavenCentral()
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "org.jetbrains.kotlin.jvm" ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.23")
                "org.jetbrains.kotlin.plugin.serialization" ->
                    useModule("org.jetbrains.kotlin:kotlin-serialization:1.9.23")
            }
        }
    }
}

rootProject.name = "junco-adapter"

includeBuild("../vendor/sparrow") {
    dependencySubstitution {
        substitute(module("com.sparrowwallet:sparrow")).using(project(":"))
        substitute(module("com.sparrowwallet:drongo")).using(project(":drongo"))
        substitute(module("com.sparrowwallet:lark")).using(project(":lark"))
    }
}
