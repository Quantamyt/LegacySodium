 import org.gradle.api.Project

object BuildConfig {
    val MINECRAFT_VERSION: String = "1.8.9"
    val FABRIC_LOADER_VERSION: String = "0.16.9"
    val FABRIC_API_VERSION: String = "1.9.4+1.8.9"

    // This value can be set to null to disable Parchment.
    val PARCHMENT_VERSION: String? = "2024.12.07"

    // https://semver.org/
    var MOD_VERSION: String = "0.7.0"

    fun createVersionString(project: Project): String {
        val builder = StringBuilder()

        val isReleaseBuild = project.hasProperty("build.release")
        val buildId = System.getenv("GITHUB_RUN_NUMBER")

        if (isReleaseBuild) {
            builder.append(MOD_VERSION)
        } else {
            builder.append(MOD_VERSION.substringBefore('-'))
            builder.append("-snapshot")
        }

        builder.append("+mc").append(MINECRAFT_VERSION)

        if (!isReleaseBuild) {
            if (buildId != null) {
                builder.append("-build.${buildId}")
            } else {
                builder.append("-local")
            }
        }

        return builder.toString()
    }
}