name := """nMiners"""

version := "1.0"

scalaVersion := "2.11.5"

libraryDependencies ++= Seq(
  "org.apache.hadoop" % "hadoop-core" % "1.2.1",
  "org.apache.mahout" % "mahout-core" % "0.9",
  "org.slf4j" % "slf4j-simple" % "1.7.5",

  "org.scalatest" % "scalatest_2.11" % "2.2.1" % "test",
  "com.typesafe" % "config" % "1.2.1"
)

//Add a new dependency repository - Typesafe Repo
resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/"