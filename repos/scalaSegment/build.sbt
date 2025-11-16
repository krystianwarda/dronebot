import scala.collection.immutable.Seq
// build.sbt
ThisBuild / organization := "com.dronebot"
ThisBuild / scalaVersion := "2.13.12"
ThisBuild / version      := "0.1.0-SNAPSHOT"

val fs2Version = "3.10.2"

unmanagedBase := baseDirectory.value / "lib"
Compile / unmanagedJars ++= (unmanagedBase.value ** "*.jar").classpath
Runtime  / unmanagedJars ++= (unmanagedBase.value ** "*.jar").classpath

lazy val osName  = System.getProperty("os.name").toLowerCase
lazy val osArch  = System.getProperty("os.arch").toLowerCase
lazy val javafxClassifier =
  if (osName.contains("win")) "win"
  else if (osName.contains("mac") && (osArch.contains("aarch64") || osArch.contains("arm"))) "mac-aarch64"
  else if (osName.contains("mac")) "mac"
  else "linux"

lazy val root = (project in file(".")).settings(
  name := "scalaSegment",
  Compile / run / fork := true,
  scalacOptions ++= Seq("-deprecation","-feature","-unchecked"),
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core"      % "2.10.0",
    "org.typelevel" %% "cats-effect"    % "3.5.4",
    "co.fs2"        %% "fs2-core"       % fs2Version,
    "co.fs2"        %% "fs2-io"         % fs2Version,
    "com.github.pureconfig" %% "pureconfig" % "0.17.8",
    "org.scalafx"   %% "scalafx"        % "20.0.0-R31",
    "org.openjfx"    % "javafx-base"     % "20.0.2" classifier javafxClassifier,
    "org.openjfx"    % "javafx-graphics" % "20.0.2" classifier javafxClassifier,
    "org.openjfx"    % "javafx-controls" % "20.0.2" classifier javafxClassifier,
    "net.java.jinput" % "jinput" % "2.0.9",
//    "net.java.jinput" % "jinput-platform" % "2.0.9" classifier "natives-all",
    "com.badlogicgames.jamepad" % "jamepad" % "2.0.20.0",
    "ch.qos.logback" % "logback-classic" % "1.4.14",
    "pl.iterators" %% "sealed-monad" % "2.0.0"
  ),
  Compile / run / javaOptions ++= Seq(
    "-Djava.library.path=C:\\tools\\DLLs",
    "-Dvpad.backend=vjoy",
    "-Dvpad.debugNative=true"
  )

)
