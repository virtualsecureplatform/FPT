ThisBuild / scalaVersion := "2.13.15"
ThisBuild / organization := "fpt"
ThisBuild / version := "0.1.0"

lazy val chiselVersion = "6.6.0"

lazy val root = (project in file("."))
  .settings(
    name := "fpt-chisel",
    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full
    ),
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "edu.berkeley.cs" %% "chiseltest" % "6.0.0" % Test,
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-language:reflectiveCalls"
    ),
    Test / parallelExecution := false
  )
