import sbt._
import Keys._
import sbtassembly.AssemblyPlugin.autoImport._

object Steenwerck extends Build {

  lazy val typesafeRepo = "Typesafe repository (releases)" at "http://repo.typesafe.com/typesafe/releases/"

  lazy val akka =
    Seq(libraryDependencies ++= Seq("com.typesafe.akka" %% "akka-actor" % "2.3.9",
				    "com.typesafe.akka" %% "akka-slf4j" % "2.3.9",
				    "ch.qos.logback" % "logback-classic" % "1.0.9" % "compile"),
        resolvers += typesafeRepo)

  lazy val assemble =
    Seq(jarName in assembly := "../../../bin/" + name.value + ".jar",
	test in assembly := {})

  lazy val scopt = Seq(libraryDependencies += "com.github.scopt" %% "scopt" % "3.3.0")

  lazy val json = Seq(libraryDependencies += "net.liftweb" %% "lift-json" % "2.6")

  lazy val jackcess =
    Seq(libraryDependencies += "com.healthmarketscience.jackcess" % "jackcess" % "1.2.9")

  lazy val mysql =
    Seq(libraryDependencies ++= Seq("commons-dbcp" % "commons-dbcp" % "1.4",
				    "commons-dbutils" % "commons-dbutils" % "1.5",
				    "mysql" % "mysql-connector-java" % "5.1.22"))

  lazy val common = Project.defaultSettings ++ assemble ++
    Seq(scalaVersion := "2.11.5",
	scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"))

  lazy val root =
    Project("root", file(".")) aggregate(replicate, wipe, stats, loader, loaderaccess)

  lazy val stats =
    Project("stats", file("stats"), settings = common ++ akka ++ scopt) dependsOn(canape)

  lazy val replicate =
    Project("replicate", file("replicate"), settings = common ++ akka ++ scopt) dependsOn(canape, config, steenwerck)

  lazy val loader =
    Project("loader", file("loader"), settings = common ++ akka ++ mysql ++ scopt) dependsOn(canape)

  lazy val loaderaccess =
    Project("loaderaccess", file("loaderaccess"), settings = common ++ akka ++ jackcess ++ scopt) dependsOn(canape)

  lazy val wipe = Project("wipe", file("wipe"), settings = common ++ akka ++ scopt) dependsOn(canape, config)

  lazy val canape = Project("canape", file("libs/canape"), settings = common)

  lazy val steenwerck = Project("steenwerck", file("libs/steenwerck"), settings = common ++ akka) dependsOn(canape)

  lazy val config = Project(id = "config", base = file("libs/config"), settings = common)
}
