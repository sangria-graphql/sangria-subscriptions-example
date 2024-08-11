name := "sangria-subscriptions-example"
version := "0.1.0-SNAPSHOT"

description := "Sangria Subscriptions Example"

scalaVersion := "2.13.14"
scalacOptions ++= Seq("-deprecation", "-feature")

libraryDependencies ++= Seq(
  "org.sangria-graphql" %% "sangria" % "2.0.0",
  "org.sangria-graphql" %% "sangria-spray-json" % "1.0.3",
  "org.sangria-graphql" %% "sangria-akka-streams" % "1.0.2",
  "com.typesafe.akka" %% "akka-http" % "10.5.1",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.5.1",
  "com.lightbend.akka" %% "akka-stream-alpakka-sse" % "6.0.1",
  "org.scalatest" %% "scalatest" % "3.0.8" % "test",
  "com.typesafe.akka" %% "akka-stream" % "2.5.23",
  "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.23" % "test"
)

resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

Revolver.settings
