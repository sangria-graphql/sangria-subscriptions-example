name := "sangria-subscriptions-example"
version := "0.1.0-SNAPSHOT"

description := "Sangria Subscriptions Example"

scalaVersion := "2.13.14"
scalacOptions ++= Seq("-deprecation", "-feature")

lazy val akkaStreamVersion = "2.8.0"
lazy val akkaHttpVersion = "10.5.3"

libraryDependencies ++= Seq(
  "org.sangria-graphql" %% "sangria" % "4.1.1",
  "org.sangria-graphql" %% "sangria-spray-json" % "1.0.3",
  "org.sangria-graphql" %% "sangria-akka-streams" % "1.0.2",
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  "com.lightbend.akka" %% "akka-stream-alpakka-sse" % "6.0.1",
  "com.typesafe.akka" %% "akka-stream" % akkaStreamVersion,
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaStreamVersion % "test",
  "org.scalatest" %% "scalatest" % "3.0.8" % "test"
)

resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

Revolver.settings
