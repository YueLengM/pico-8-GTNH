
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.shadowJar {
    relocate("org.slf4j", "com.yuelengm.pico8gtnh.shadow.org.slf4j")
}
