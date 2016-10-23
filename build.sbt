name := "sangria-subscriptions-example"
version := "0.1.0-SNAPSHOT"

description := "Sangria Subscriptions Example"

scalaVersion := "2.11.8"
scalacOptions ++= Seq("-deprecation", "-feature")

libraryDependencies ++= Seq(
  "org.sangria-graphql" %% "sangria" % "1.0.0-RC2",
  "org.sangria-graphql" %% "sangria-spray-json" % "0.3.1",

  "com.typesafe.akka" %% "akka-http-experimental" % "2.4.11",
  "com.typesafe.akka" %% "akka-http-spray-json-experimental" % "2.4.11",
  "de.heikoseeberger" %% "akka-sse" % "1.11.0",

  "org.scalatest" %% "scalatest" % "3.0.0" % "test",
  "com.typesafe.akka" %% "akka-stream-testkit-experimental" % "2.0.5" % "test"
)

resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

Revolver.settings