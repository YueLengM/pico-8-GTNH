import org.gradle.api.tasks.AbstractCopyTask
import org.gradle.kotlin.dsl.named

val embedOnly = configurations.getByName("embedOnly")

tasks.named<AbstractCopyTask>("shadowJar") {
    dependsOn(embedOnly)
    from(embedOnly.map { project.zipTree(it) })
}
