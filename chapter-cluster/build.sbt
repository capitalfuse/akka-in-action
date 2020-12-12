name := "words-cluster"

version := "1.0"

organization := "com.manning"

libraryDependencies ++= {
  val akkaVersion = "2.6.10"
  Seq(
    "com.typesafe.akka"       %% "akka-actor"                  % akkaVersion,
    "com.typesafe"            %  "config"                      % "1.4.1",
    // https://mvnrepository.com/artifact/org.slf4j/slf4j-api
    "org.slf4j"               %  "slf4j-api"                   % "1.7.30",
    "com.typesafe.akka"       %% "akka-slf4j"                  % akkaVersion,
    "com.typesafe.akka"       %% "akka-remote"                 % akkaVersion,
    // Artery UDP depends on Aeron. This needs to be explicitly added as a dependency if using aeron-udp
    "io.aeron"                % "aeron-driver"                 % "1.30.0",
    "io.aeron"                % "aeron-client"                 % "1.30.0",
    "com.typesafe.akka"       %% "akka-cluster-typed"          % akkaVersion,
    "com.typesafe.akka"       %% "akka-multi-node-testkit"     % akkaVersion   % "test",
    "com.typesafe.akka"       %% "akka-testkit"                % akkaVersion   % "test",
    "org.scalatest"           %% "scalatest"                   % "3.2.3"       % "test",
    "ch.qos.logback"          %  "logback-classic"             % "1.2.3"
  )
}

// Assembly settings
mainClass in assembly := Some("aia.cluster.words.Main")

assemblyJarName in assembly := "words-node.jar"