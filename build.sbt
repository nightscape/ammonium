import scalatex.ScalatexReadme

scalaVersion := "2.12.2"

crossScalaVersions := Seq(
  "2.10.4", "2.10.5", "2.10.6", "2.11.3",
  "2.11.4", "2.11.5", "2.11.6", "2.11.7", "2.11.8", "2.11.9"
)

val dontPublishSettings = Seq(
  publishArtifact := false,
  publishTo := Some(Resolver.file("Unused transient repository", file("target/unusedrepo")))
)

dontPublishSettings

val macroSettings = Seq(
  libraryDependencies ++= Seq(
    "org.scala-lang" % "scala-reflect" % scalaVersion.value % Provided
  ) ++ (
    if (!scalaVersion.value.startsWith("2.10.")) Nil
    else Seq(
      compilerPlugin("org.scalamacros" % s"paradise" % "2.0.1" cross CrossVersion.full),
      "org.scalamacros" %% s"quasiquotes" % "2.0.1"
    )
  )
)

val sharedSettings = Seq(

  scalaVersion := "2.12.2",
  organization := "org.jupyter-scala",
  version := _root_.ammonite.Constants.version,
  libraryDependencies += "com.lihaoyi" %% "utest" % "0.4.5" % Test,
  // Needed for acyclic to work...
  libraryDependencies ++= {
    if (!scalaVersion.value.startsWith("2.10.")) Nil
    else Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value % Provided
    )
  },
  testFrameworks := Seq(new TestFramework("utest.runner.Framework")),
  scalacOptions += "-target:jvm-1.7",
  scalacOptions += "-P:acyclic:force",
  autoCompilerPlugins := true,
  addCompilerPlugin("com.lihaoyi" %% "acyclic" % "0.1.7"),
  ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) },
  parallelExecution in Test := !scalaVersion.value.contains("2.10"),
  (unmanagedSources in Compile) += (baseDirectory in ThisBuild).value/"project"/"Constants.scala",
  mappings in (Compile, packageSrc) += {
    ((baseDirectory in ThisBuild).value/".."/"project"/"Constants.scala") -> "Constants.scala"
  },
  libraryDependencies ++= Seq(
    "com.lihaoyi" %% "acyclic" % "0.1.7" % Provided
  ) ,
  publishMavenStyle := true,
  pomIncludeRepository := { _ => false },
  publishTo := Some {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      "snapshots" at nexus + "content/repositories/snapshots"
    else
      "releases" at nexus + "service/local/staging/deploy/maven2"
  },
  credentials ++= {
    Seq("SONATYPE_USER", "SONATYPE_PASS").map(sys.env.get) match {
      case Seq(Some(user), Some(pass)) =>
        Seq(Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", user, pass))
      case _ =>
        Seq()
    }
  },
  pomExtra :=
    <url>https://github.com/alexarchambault/ammonium</url>
      <licenses>
        <license>
          <name>MIT license</name>
          <url>http://www.opensource.org/licenses/mit-license.php</url>
        </license>
      </licenses>
      <scm>
        <url>git://github.com/alexarchambault/ammonium.git</url>
        <connection>scm:git://github.com/alexarchambault/ammonium.git</connection>
      </scm>
      <developers>
        <developer>
          <id>alexarchambault</id>
          <name>Alexandre Archambault</name>
          <url>https://github.com/alexarchambault</url>
        </developer>
      </developers>
)

/**
 * Concise, type-safe operating-system operations in Scala: filesystem,
 * subprocesses, and other such things.
 */
lazy val ops = project
  .settings(
    sharedSettings,
    libraryDependencies += "com.lihaoyi" %% "geny" % "0.1.2",
    name := "ammonite-ops"
  )


/**
 * A standalone re-implementation of a composable readline-style REPL,
 * without any behavior associated with it. Contains an echo-repl that
 * can be run to test the REPL interactions
 */
lazy val terminal = project
  .settings(
    sharedSettings,
    name := "ammonite-terminal",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "sourcecode" % "0.1.3",
      "com.lihaoyi" %% "fansi" % "0.2.3"
    ),
    macroSettings
  )


/**
 * A better Scala REPL, which can be dropped in into any project or run
 * standalone in a Scala project to provide a better interactive experience
 * for Scala
 */
lazy val amm = project
  .dependsOn(
    terminal, ops,
    ammUtil, ammRuntime, ammInterp, ammRepl
  )
  .settings(
    macroSettings,
    sharedSettings,
    packAutoSettings,
    crossVersion := CrossVersion.full,
    test in assembly := {},
    // publish test artifacts, see https://stackoverflow.com/questions/16389446/compile-tests-with-sbt-and-package-them-to-be-run-later/16409096#16409096
    publishArtifact in (Test, packageBin) := true,
    publishArtifact in (Test, packageDoc) := true,
    publishArtifact in (Test, packageSrc) := true,
    name := "ammonite",
    libraryDependencies ++= (
      if (scalaVersion.value startsWith "2.10.") Nil
      else Seq("com.chuusai" %% "shapeless" % "2.3.2" % Test)
    ),
    javaOptions += "-Xmx4G",
    assemblyOption in assembly := (assemblyOption in assembly).value.copy(
      prependShellScript = Some(
        // G1 Garbage Collector is awesome https://github.com/lihaoyi/Ammonite/issues/216
        Seq("#!/usr/bin/env sh", """exec java -jar -Xmx500m -XX:+UseG1GC $JAVA_OPTS "$0" "$@"""")
      )
    ),
    assemblyJarName in assembly := s"${name.value}-${version.value}-${scalaVersion.value}",
    assembly in Test := {
      val dest = target.value/"amm"
      IO.copyFile(assembly.value, dest)
      import sys.process._
      Seq("chmod", "+x", dest.getAbsolutePath).!
      dest
    },
    parallelExecution in Test := false
  )

lazy val ammUtil = project
  .in(file("amm/util"))
  .dependsOn(ops)
  .settings(
    macroSettings,
    sharedSettings,
    name := "ammonite-util",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "0.4.4",
      "com.lihaoyi" %% "pprint" % "0.5.2",
      "com.lihaoyi" %% "fansi" % "0.2.4"
    )
  )

lazy val ammRuntime = project
  .in(file("amm/runtime"))
  .dependsOn(ops, ammUtil)
  .enablePlugins(coursier.ShadingPlugin)
  .settings(
    macroSettings,
    sharedSettings,

    name := "ammonite-runtime",
    inConfig(coursier.ShadingPlugin.Shading)(com.typesafe.sbt.pgp.PgpSettings.projectSettings),
     // ytf does this have to be repeated here?
     // Can't figure out why configuration get lost without this in particular...
    coursier.ShadingPlugin.projectSettings,
    shadingNamespace := "ammonite.shaded",
    shadeNamespaces ++= Set(
      "coursier",
      "scalaz",
      "org.jsoup"
    ),
    publish := publish.in(Shading).value,
    publishLocal := publishLocal.in(Shading).value,
    PgpKeys.publishSigned := PgpKeys.publishSigned.in(Shading).value,
    PgpKeys.publishLocalSigned := PgpKeys.publishLocalSigned.in(Shading).value,
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      "io.get-coursier" %% "coursier-cache" % "1.0.0-RC3" % "shaded",
      "org.scalaj" %% "scalaj-http" % "2.3.0"
    )
  )


lazy val ammInterp = project
  .in(file("amm/interp"))
  .dependsOn(ops, ammUtil, ammRuntime)
  .settings(
    macroSettings,
    sharedSettings,
    crossVersion := CrossVersion.full,

    name := "ammonite-compiler",
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-reflect" % scalaVersion.value,
      "com.lihaoyi" %% "scalaparse" % "0.4.3"
    ),
    unmanagedSourceDirectories in Compile ++= {
      if (Set("2.10", "2.11").contains(scalaBinaryVersion.value))
        Seq(baseDirectory.value / "src" / "main" / "scala-2.10_2.11")
      else
        Seq()
    }
  )


lazy val ammRepl = project
  .in(file("amm/repl"))
  .dependsOn(terminal, ammUtil, ammRuntime, ammInterp)
  .settings(
    macroSettings,
    sharedSettings,
    crossVersion := CrossVersion.full,
    name := "ammonite-repl",
    libraryDependencies ++= Seq(
      "jline" % "jline" % "2.14.3",
      "com.github.scopt" %% "scopt" % "3.5.0"
    )
  )

/**
 * Project that binds together [[ops]] and [[amm]], turning them into a
 * credible systems shell that can be used to replace bash/zsh/etc. for
 * common housekeeping tasks
 */
lazy val shell = project
  .dependsOn(ops, amm % "compile->compile;test->test")
  .settings(
    sharedSettings,
    macroSettings,
    crossVersion := CrossVersion.full,
    name := "ammonite-shell",
    (test in Test) := (test in Test).dependsOn(packageBin in Compile).value,
    (run in Test) := (run in Test).dependsOn(packageBin in Compile).evaluated,
    (testOnly in Test) := (testOnly in Test).dependsOn(packageBin in Compile).evaluated
  )

val integrationTasks = Seq(
  assembly in amm,
  packageBin in (shell, Compile)
)

lazy val integration = project
  .dependsOn(ops)
  .dependsOn(amm)
  .settings(
    sharedSettings,
    (test in Test) := (test in Test).dependsOn(integrationTasks:_*).value,
    (run in Test) := (run in Test).dependsOn(integrationTasks:_*).evaluated,
    (testOnly in Test) := (testOnly in Test).dependsOn(integrationTasks:_*).evaluated,
    (console in Test) := (console in Test).dependsOn(integrationTasks:_*).value,
    parallelExecution in Test := false,
    dontPublishSettings,
    initialCommands in (Test, console) := "ammonite.integration.Main.main(null)"
  )


/**
 * REPL available via remote ssh access.
 * Plug into any app environment for live hacking on a live application.
 */
lazy val sshd = project
    .dependsOn(amm)
    .settings(
      sharedSettings,
      crossVersion := CrossVersion.full,
      name := "ammonite-sshd",
      libraryDependencies ++= Seq(
        // sshd-core 1.3.0 requires java8
        "org.apache.sshd" % "sshd-core" % "1.2.0",
        "org.bouncycastle" % "bcprov-jdk15on" % "1.56",
        //-- test --//
        // slf4j-nop makes sshd server use logger that writes into the void
        "org.slf4j" % "slf4j-nop" % "1.7.12" % Test,
        "com.jcraft" % "jsch" % "0.1.54" % Test,
        "org.scalacheck" %% "scalacheck" % "1.12.6" % Test
      )
  )

lazy val readme = ScalatexReadme(
  projectId = "readme",
  wd = file(""),
  url = "https://github.com/lihaoyi/ammonite/tree/master",
  source = "Index"
).settings(
  dontPublishSettings,
  scalaVersion := "2.12.2",
  libraryDependencies += "com.lihaoyi" %% "fansi" % "0.2.3",
  (run in Compile) := (run in Compile).dependsOn(
    assembly in (amm, Test),
    packageBin in (shell, Compile),
    doc in (ops, Compile),
    doc in (terminal, Compile),
    doc in (amm, Compile),
    doc in (sshd, Compile),
    doc in (shell, Compile)
  ).evaluated,
  (run in Compile) := (run in Compile).dependsOn(Def.task{
    val apiFolder = (target in Compile).value/"scalatex"/"api"
    val copies = Seq(
      (doc in (ops, Compile)).value -> "ops",
      (doc in (terminal, Compile)).value -> "terminal",
      (doc in (amm, Compile)).value -> "amm",
      (doc in (sshd, Compile)).value -> "sshd",
      (doc in (shell, Compile)).value -> "shell"
    )
    for ((folder, name) <- copies){
      sbt.IO.copyDirectory(folder, apiFolder/name, overwrite = true)
    }
  }).evaluated,
  (unmanagedSources in Compile) += baseDirectory.value/".."/"project"/"Constants.scala"
)

// Only modules down-stream of `ammInterp` need to be fully cross-built against
// minor versions, since `interp` depends on compiler internals. The modules
// upstream of `ammInterp` can be cross-built normally only against major versions
// of Scala
lazy val singleCrossBuilt = project
  .in(file("target/singleCrossBuilt"))
  .aggregate(ops, terminal, ammUtil, ammRuntime)
  .settings(dontPublishSettings)

lazy val fullCrossBuilt = project
  .in(file("target/fullCrossBuilt"))
  .aggregate(shell, amm, sshd, ammInterp, ammRepl)
  .settings(dontPublishSettings)


lazy val published = project
  .in(file("target/published"))
  .aggregate(fullCrossBuilt, singleCrossBuilt)
  .settings(dontPublishSettings)
