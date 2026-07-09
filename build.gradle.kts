plugins {
    id("org.springframework.boot") version "3.4.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    kotlin("jvm") version "2.1.0" apply false
    kotlin("plugin.spring") version "2.1.0" apply false
    kotlin("plugin.jpa") version "2.1.0" apply false
}

subprojects {
    group = "com.booking"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

tasks.register("test") {
    dependsOn(":reservation:test", ":payment:test")
}

tasks.register("build") {
    dependsOn(":reservation:build", ":payment:build")
}

tasks.register("bootRun") {
    dependsOn(":reservation:bootRun")
}
