name := "zio-task-manager"
version := "0.1.0"
scalaVersion := "2.13.12"

val zioVersion   = "2.0.21"
val tapirVersion = "1.9.11"

libraryDependencies ++= Seq(
  // ZIO
  "dev.zio" %% "zio"      % zioVersion,
  "dev.zio" %% "zio-json" % "0.6.2",

  // Tapir + Akka HTTP
  "com.softwaremill.sttp.tapir" %% "tapir-akka-http-server"  % tapirVersion,
  "com.softwaremill.sttp.tapir" %% "tapir-json-zio"          % tapirVersion,
  "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % tapirVersion,

  // Logging
  "ch.qos.logback" % "logback-classic" % "1.4.14",

  // Test
  "dev.zio" %% "zio-test"     % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt" % zioVersion % Test
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
