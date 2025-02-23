import scala.scalanative.build._
import scala.util.Properties

import Dependencies._
import sbtcrossproject.CrossPlugin.autoImport.crossProject

def parseTagVersion: String = {
  import scala.sys.process._
  // drop `v` prefix
  "git describe --abbrev=0 --tags".!!.drop(1).trim
}
def localSnapshotVersion: String = s"$parseTagVersion-SNAPSHOT"
def isCI = System.getenv("CI") != null

def scala212 = "2.12.20"
def scala213 = "2.13.16"

inThisBuild {
  List(
    version ~= { dynVer =>
      if (isCI) dynVer else localSnapshotVersion // only for local publishing
    },
    organization := "org.scalameta",
    homepage := Some(url("https://github.com/scalameta/scalafmt")),
    licenses :=
      List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(Developer(
      "olafurpg",
      "Ólafur Páll Geirsson",
      "olafurpg@gmail.com",
      url("https://geirsson.com"),
    )),
    scalaVersion := scala213,
    crossScalaVersions := List(scala213, scala212),
    resolvers ++= Resolver.sonatypeOssRepos("releases"),
    resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
    testFrameworks += new TestFramework("munit.Framework"),
  )
}

name := "scalafmtRoot"
publish / skip := true

lazy val copyScalaNative = taskKey[Unit]("Copy Scala Native output to root")

copyScalaNative := {
  val binaryVersion = (cli.native / scalaBinaryVersion).value
  val suffix = if (Properties.isWin) ".exe" else ""
  val nativeOutput = (cli.native / Compile / target).value /
    s"scala-$binaryVersion" / s"scalafmt-cli$suffix"
  val output = baseDirectory.value / s"scalafmt$suffix"
  IO.copyFile(nativeOutput, output)
}

addCommandAlias(
  "scala-native",
  "cliNative/compile;cliNative/nativeLink;copyScalaNative",
)

commands ++= Seq(
  Command.command("ci-test-jvm") { s =>
    val docsTest =
      if (scalaBinaryVersion.value == "2.12") "docs/run" else "version"
    "tests/test" :: "cli/test" :: "publishLocal" :: docsTest :: s
  },
  Command.command("ci-test-native")(s =>
    // for native, we don't publish nor build docs
    "testsNative/test" :: "cliNative/test" :: s,
  ),
)

lazy val dynamic = crossProject(JVMPlatform) // don't build for NativePlatform
  .withoutSuffixFor(JVMPlatform).in(file("scalafmt-dynamic")).settings(
    moduleName := "scalafmt-dynamic",
    description := "Implementation of scalafmt-interfaces",
    buildInfoSettings("org.scalafmt.dynamic", "BuildInfo"),
    libraryDependencies ++= List(
      "io.get-coursier" % "interface" % "1.0.27",
      "com.typesafe" % "config" % "1.4.3",
    ),
    sharedTestSettings,
    scalacOptions ++= scalacJvmOptions.value,
  ).dependsOn(interfaces, sysops).dependsOn(core % "test")
  .enablePlugins(BuildInfoPlugin)

lazy val interfaces = crossProject(JVMPlatform, NativePlatform)
  .withoutSuffixFor(JVMPlatform).in(file("scalafmt-interfaces")).settings(
    moduleName := "scalafmt-interfaces",
    description :=
      "Dependency-free, pure Java public interfaces to integrate with Scalafmt through a build tool or editor plugin.",
    Compile / resourceGenerators += Def.task {
      val out = (Compile / managedResourceDirectories).value.head /
        "scalafmt.properties"
      val props = new java.util.Properties()
      props.put("version", version.value)
      IO.write(props, "scalafmt properties", out)
      List(out)
    },
  ).jvmSettings(
    javacOptions ++= Seq("-source", "8", "-target", "8"),
    Compile / doc / javacOptions := Seq(),
    crossVersion := CrossVersion.disabled,
    autoScalaLibrary := false,
  )

lazy val sysops = crossProject(JVMPlatform, NativePlatform)
  .withoutSuffixFor(JVMPlatform).in(file("scalafmt-sysops")).settings(
    moduleName := "scalafmt-sysops",
    description := "Scalafmt systems operations",
    scalacOptions ++= scalacJvmOptions.value,
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 12)) =>
          Seq("com.github.bigwheel" %% "util-backports" % "2.1")
        case Some((2, 13)) =>
          Seq("org.scala-lang.modules" %% "scala-parallel-collections" % "1.2.0")
        case _ => Seq()
      }
    },
    sharedTestSettings,
  )

lazy val config = crossProject(JVMPlatform, NativePlatform)
  .withoutSuffixFor(JVMPlatform).in(file("scalafmt-config")).settings(
    moduleName := "scalafmt-config",
    description := "Scalafmt config parsing",
    scalacOptions ++= scalacJvmOptions.value,
    libraryDependencies ++= Seq(metaconfig.value),
  ).jvmSettings(libraryDependencies ++= Seq(metaconfigTypesafe.value))
  .nativeSettings(libraryDependencies ++= Seq(metaconfigSconfig.value))
// .jsSettings(
//   libraryDependencies ++= Seq(
//     metaconfigHocon.value,
//   )
// )

lazy val core = crossProject(JVMPlatform, NativePlatform)
  .in(file("scalafmt-core")).settings(
    moduleName := "scalafmt-core",
    buildInfoSettings("org.scalafmt", "Versions"),
    scalacOptions ++= scalacJvmOptions.value,
    libraryDependencies ++= Seq("org.scalameta" %%% "mdoc-parser" % mdocV),
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 13)) => Seq()
        case _ => Seq(compilerPlugin(
            "org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full,
          ))
      }
    },
  )
  // .jsSettings(
  //   libraryDependencies ++= List(
  //     scalatest.value % Test // must be here for coreJS/test to run anything
  //   )
  // )
  .nativeSettings(libraryDependencies += "com.lihaoyi" %%% "fastparse" % "3.1.1")
  .aggregate(sysops, config, macros).dependsOn(sysops, config, macros)
  .enablePlugins(BuildInfoPlugin)
lazy val coreJVM = core.jvm
// lazy val coreJS = core.js

lazy val macros = crossProject(JVMPlatform, NativePlatform)
  .in(file("scalafmt-macros")).settings(
    moduleName := "scalafmt-macros",
    scalacOptions ++= scalacJvmOptions.value,
    libraryDependencies ++= Seq(
      scalameta.value,
      "org.scala-lang" % "scala-reflect" % scalaVersion.value,
    ),
  )

import sbtassembly.AssemblyPlugin.defaultUniversalScript

val scalacJvmOptions = Def.setting {
  val cross = CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 13)) =>
      Seq("-Ymacro-annotations", "-Xfatal-warnings", "-deprecation:false")
    case _ => Seq.empty
  }

  val unused = Seq("imports", "privates", "locals", "patvars", "implicits")
    .map(x => s"-Ywarn-unused:$x")

  cross ++ unused ++ Seq("-target:8", "-release:8")
}

lazy val cli = crossProject(JVMPlatform, NativePlatform)
  .withoutSuffixFor(JVMPlatform).in(file("scalafmt-cli")).settings(
    moduleName := "scalafmt-cli",
    assembly / mainClass := Some("org.scalafmt.cli.Cli"),
    assembly / assemblyOption := (assembly / assemblyOption).value
      .withPrependShellScript(Some(defaultUniversalScript(shebang = false))),
    assembly / assemblyJarName := "scalafmt.jar",
    assembly / assemblyMergeStrategy := {
      case "reflect.properties" => MergeStrategy.first
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit-diff" % munitV,
      "com.martiansoftware" % "nailgun-server" % "0.9.1",
      "com.github.scopt" %%% "scopt" % "4.1.0",
    ),
    scalacOptions ++= scalacJvmOptions.value,
    Compile / mainClass := Some("org.scalafmt.cli.Cli"),
    sharedTestSettings,
  ).nativeSettings(scalaNativeConfig).dependsOn(core, interfaces)
  .jvmConfigure(_.dependsOn(dynamic.jvm).aggregate(dynamic.jvm, core.jvm))

lazy val tests = crossProject(JVMPlatform, NativePlatform)
  .withoutSuffixFor(JVMPlatform).in(file("scalafmt-tests")).settings(
    publish / skip := true,
    sharedTestSettings,
    libraryDependencies += scalametaTestkit.value % Test,
    libraryDependencies += "com.lihaoyi" %%% "scalatags" % "0.13.1" % Test,
    scalacOptions ++= scalacJvmOptions.value,
    buildInfoPackage := "org.scalafmt.tests",
    buildInfoKeys := Seq[BuildInfoKey]("resourceDirectory" -> {
      val sharedTests = (baseDirectory.value.getParentFile / "shared").toPath
      (Test / resourceDirectories).value.find(_.toPath.startsWith(sharedTests))
        .get
    }),
  ).enablePlugins(BuildInfoPlugin)
  .jvmSettings(javaOptions += "-Dfile.encoding=UTF8").dependsOn(core, cli)

lazy val sharedTestSettings = Seq(libraryDependencies += munit.value % Test)

lazy val communityTestsCommon = crossProject(JVMPlatform, NativePlatform)
  .withoutSuffixFor(JVMPlatform)
  .configureCross(confCommunityTestShared("scalafmt-tests-community/common"))
  .dependsOn(core)

lazy val communityTestsScala2 = crossProject(JVMPlatform, NativePlatform)
  .withoutSuffixFor(JVMPlatform)
  .configureCross(confCommunityTest("scalafmt-tests-community/scala2"))

lazy val communityTestsScala3 = crossProject(JVMPlatform, NativePlatform)
  .withoutSuffixFor(JVMPlatform)
  .configureCross(confCommunityTest("scalafmt-tests-community/scala3"))

lazy val communityTestsSpark = crossProject(JVMPlatform, NativePlatform)
  .withoutSuffixFor(JVMPlatform)
  .configureCross(confCommunityTest("scalafmt-tests-community/spark"))

lazy val communityTestsIntellij = crossProject(JVMPlatform, NativePlatform)
  .withoutSuffixFor(JVMPlatform)
  .configureCross(confCommunityTest("scalafmt-tests-community/intellij"))

lazy val communityTestsOther = crossProject(JVMPlatform, NativePlatform)
  .withoutSuffixFor(JVMPlatform)
  .configureCross(confCommunityTest("scalafmt-tests-community/other"))

def confCommunityTestShared(where: String)(
    project: sbtcrossproject.CrossProject,
) = project.in(file(where)).settings(communityTestsSettings)
  .settings(sharedTestSettings).nativeSettings(scalaNativeConfig)

def confCommunityTest(where: String)(project: sbtcrossproject.CrossProject) =
  project.configureCross(confCommunityTestShared(where))
    .dependsOn(communityTestsCommon % "test->test")

lazy val benchmarks = project.in(file("scalafmt-benchmarks")).settings(
  publish / skip := true,
  moduleName := "scalafmt-benchmarks",
  libraryDependencies += scalametaTestkit.value,
  run / javaOptions ++= Seq(
    "-Djava.net.preferIPv4Stack=true",
    "-XX:+AggressiveOpts",
    "-XX:+UseParNewGC",
    "-XX:+UseConcMarkSweepGC",
    "-XX:+CMSParallelRemarkEnabled",
    "-XX:+CMSClassUnloadingEnabled",
    "-XX:ReservedCodeCacheSize=128m",
    "-XX:MaxMetaspaceSize=1024m",
    "-XX:SurvivorRatio=128",
    "-XX:MaxTenuringThreshold=0",
    "-Xss8M",
    "-Xms512M",
    "-Xmx2G",
    "-server",
  ),
).dependsOn(coreJVM).enablePlugins(JmhPlugin)

lazy val docs = project.in(file("scalafmt-docs")).settings(
  crossScalaVersions := List(scala212),
  publish / skip := true,
  mdoc := (Compile / run).evaluated,
).dependsOn(cli.jvm, dynamic.jvm).enablePlugins(DocusaurusPlugin)

val V = "\\d+\\.\\d+\\.\\d+"
val ReleaseCandidate = s"($V-RC\\d+).*".r
val Milestone = s"($V-M\\d+).*".r

lazy val stableVersion = Def
  .setting((ThisBuild / version).value.replaceAll("\\+.*", ""))

def buildInfoSettings(pkg: String, obj: String): Seq[Def.Setting[_]] = Seq(
  buildInfoKeys := Seq[BuildInfoKey](
    name,
    version,
    "scalameta" -> scalametaV,
    "nightly" -> version.value,
    "stable" -> stableVersion.value,
    "previousStable" ->
      previousStableVersion.value.getOrElse(stableVersion.value),
    "scala" -> scalaVersion.value,
    "scala212" -> scala212,
    "coursier" -> coursier,
    "commit" -> sys.process.Process("git rev-parse HEAD").lineStream_!.head,
    "timestamp" -> System.currentTimeMillis().toString,
    scalaVersion,
    sbtVersion,
  ),
  buildInfoPackage := pkg,
  buildInfoObject := obj,
)

lazy val communityTestsSettings: Seq[Def.Setting[_]] = Seq(
  publish / skip := true,
  scalacOptions ++= scalacJvmOptions.value,
  javaOptions += "-Dfile.encoding=UTF8",
)

lazy val scalaNativeConfig = nativeConfig ~= { _.withMode(Mode.releaseFull) }
