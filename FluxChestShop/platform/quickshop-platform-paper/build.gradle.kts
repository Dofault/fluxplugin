plugins {
    id("quickshop.core-conventions")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}") // FluxChestShop : cf. gradle.properties
    api(project(":platform:quickshop-platform-interface"))
}
