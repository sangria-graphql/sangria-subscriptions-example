name := "sangria-subscriptions-example"
version := "0.2.0-SNAPSHOT"

description := "Sangria Subscriptions Example"

scalaVersion := "2.12.6"
scalacOptions ++= Seq("-deprecation", "-feature")

libraryDependencies ++= Seq(
  "org.sangria-graphql" %% "sangria" % "1.3.1",
  "org.sangria-graphql" %% "sangria-spray-json" % "1.0.1",
  "org.sangria-graphql" %% "sangria-akka-streams" % "1.0.1",

  "com.typesafe.akka" %% "akka-http" % "10.1.4",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.4",
  "de.heikoseeberger" %% "akka-sse" % "3.0.0",

  "org.scalatest" %% "scalatest" % "3.0.5" % "test",

  // akka-http still depends on 2.4 but should work with 2.5 without problems
  // see https://github.com/akka/akka-http/issues/821
  "com.typesafe.akka" %% "akka-stream" % "2.5.14",
  "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.14" % "test"
)

resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

Revolver.settings
