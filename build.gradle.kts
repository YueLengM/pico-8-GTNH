
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

val embedOnly: Configuration by configurations

tasks.shadowJar {
    dependsOn(embedOnly)
    from(embedOnly.map(::zipTree))
}
