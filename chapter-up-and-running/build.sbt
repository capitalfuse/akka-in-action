name := "goticks"

version := "1.0"

organization := "com.goticks"

enablePlugins(JavaServerAppPackaging)

libraryDependencies ++= {
  val akkaVersion = "2.6.10"
  val AkkaHttpVersion = "10.2.1"
  Seq(
    "com.typesafe.akka" %% "akka-actor"      % akkaVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.typesafe.akka" %% "akka-http-core"  % AkkaHttpVersion, 
    "com.typesafe.akka" %% "akka-http"  % AkkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-spray-json"  % AkkaHttpVersion, 
    "com.typesafe.akka" %% "akka-slf4j"      % akkaVersion,
    "ch.qos.logback"     % "logback-classic" % "1.3.0-alpha5",
    "com.typesafe.akka" %% "akka-testkit"    % akkaVersion   % "test",
    "org.scalatest"     %% "scalatest"       % "3.2.3"       % "test"
  )
}