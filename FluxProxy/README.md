# FluxProxy

Plugin Velocity (proxy `01_velocity`) : masque dans les suggestions « / » les commandes du proxy (velocity, cb, slp,
planproxy, staffsuiteproxy...) sauf celles listées dans `plugins/fluxproxy/commandes.txt` (relu à chaque changement).
Les commandes des serveurs ne sont pas touchées, Flux les filtre de son côté.

Compilation : `libs/velocity.jar` = copie de `01_velocity/velocity.jar` (classes Java 25), donc JDK 25 :

    javac --release 21 -proc:none -cp libs/velocity.jar -d target/classes $(find src/main/java -name '*.java')
    cp src/main/resources/* target/classes/ && jar cf target/FluxProxy.jar -C target/classes .
