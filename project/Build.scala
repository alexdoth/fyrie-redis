import sbt._
import Keys._

import com.typesafe.sbtscalariform.ScalariformPlugin
import com.typesafe.sbtscalariform.ScalariformPlugin.ScalariformKeys

object FyrieRedisBuild extends Build {
  lazy val core = Project("fyrie-redis",
                          file("."),
                          settings = coreSettings)

  val coreSettings = Defaults.defaultSettings ++ ScalariformPlugin.settings ++ Seq(
    scalaVersion := "2.9.1",
    crossScalaVersions := Seq("2.9.0-1", "2.9.1"),
    name := "fyrie-redis",
    organization := "net.fyrie",
    version := "1.2-SNAPSHOT",

    resolvers += "GuiceyFruit" at "http://guiceyfruit.googlecode.com/svn/repo/releases/",

    resolvers += "Akka Repo" at "http://akka.io/repository",
    libraryDependencies ++= Seq("se.scalablesolutions.akka" % "akka-actor" % "1.2" % "compile",
                                "se.scalablesolutions.akka" % "akka-testkit" % "1.2",
                                "org.specs2" % "specs2_2.9.1" % "1.6.1",
                                "org.specs2" % "specs2-scalaz-core_2.9.1" % "6.0.1" % "test"),

    resolvers += "setak" at "http://mir.cs.illinois.edu/setak/snapshots/",
    libraryDependencies += "edu.illinois" %% "setak" % "1.0-SNAPSHOT",

    parallelExecution := false,
    scalacOptions ++= Seq("-encoding", "UTF-8", "-deprecation", "-unchecked", "-optimize"),
    ScalariformKeys.preferences in Compile := formattingPreferences,
    ScalariformKeys.preferences in Test :=  formattingPreferences,

    publishTo <<= (version) { version: String =>
      val repo = (s: String) =>
        Resolver.ssh(s, "repo.fyrie.net", "/home/repo/" + s + "/") as("derek", file("/home/derek/.ssh/id_rsa")) withPermissions("0644")
      Some(if (version.trim.endsWith("SNAPSHOT")) repo("snapshots") else repo("releases"))
    })


  val formattingPreferences = {
    import scalariform.formatter.preferences._
    FormattingPreferences()
    .setPreference(RewriteArrowSymbols, true)
    .setPreference(AlignParameters, true)
    .setPreference(AlignSingleLineCaseStatements, true)
  }
 
}

