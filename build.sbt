ThisBuild / scalaVersion := "2.13.10"
ThisBuild / version      := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "riscv-pipeline",
    libraryDependencies ++= Seq(
      "edu.berkeley.cs" %% "chisel3"    % "3.5.6",
      "edu.berkeley.cs" %% "chiseltest" % "0.5.4" % Test,
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
    ),
    addCompilerPlugin(
      "edu.berkeley.cs" % "chisel3-plugin" % "3.5.6" cross CrossVersion.full
    ),
    Test / fork := true,
  )
