addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.3")

addSbtPlugin("com.lihaoyi" % "scalatex-sbt-plugin" % "0.3.7")

addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "1.0.0")

addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.0.0")

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "1.1")

addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-RC2")
addSbtPlugin("io.get-coursier" % "sbt-shading" % "1.0.0-RC2")

addSbtPlugin("org.xerial.sbt" % "sbt-pack" % "0.8.0")

resolvers += "jboss-releases" at "https://repository.jboss.org/nexus/content/repositories/public/"
