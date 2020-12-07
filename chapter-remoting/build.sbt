name := "goticks"

version := "1.0"

organization := "com.goticks"

enablePlugins(JavaAppPackaging)

libraryDependencies ++= {
  val akkaVersion = "2.6.10"
  val akkaHttpVersion ="10.2.1"
  Seq(
    "com.typesafe.akka" %%  "akka-actor"              % akkaVersion,
    "com.typesafe.akka" %%  "akka-slf4j"              % akkaVersion,

    "com.typesafe.akka" %%  "akka-remote"             % akkaVersion,
    "com.typesafe.akka" %%  "akka-multi-node-testkit" % akkaVersion % "test",

    "com.typesafe.akka" %%  "akka-testkit"            % akkaVersion % "test",
    "org.scalatest"     %%  "scalatest"               % "3.2.3"     % "test",
    "com.typesafe.akka" %%  "akka-http-core"          % akkaHttpVersion,
    "com.typesafe.akka" %%  "akka-http"               % akkaHttpVersion,
    "com.typesafe.akka" %%  "akka-http-spray-json"    % akkaHttpVersion,
    "ch.qos.logback"    %   "logback-classic"         % "1.3.0-alpha5"
  )
}
