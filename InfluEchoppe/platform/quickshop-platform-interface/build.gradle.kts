plugins {
    id("quickshop.core-conventions")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}") // InfluEchoppe : cf. gradle.properties
    api(project(":quickshop-common"))
    compileOnly("de.tr7zw:item-nbt-api-plugin:2.15.0") {
        isTransitive = false
    }
}
