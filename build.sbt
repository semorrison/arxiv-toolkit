name := "arxiv-toolkit"

organization := "net.tqft"

version := "0.1.1-SNAPSHOT"

scalaVersion := "2.11.12"

scalacOptions += "-target:jvm-1.7"

resolvers ++= Seq(
	"Java.net Maven2 Repository" at "http://download.java.net/maven/2/",
	"tqft.net Maven repository" at "https://tqft.net/releases",
	"Sonatype Nexus Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
	"Sonatype Nexus Releases" at "https://oss.sonatype.org/content/repositories/releases",
	"Scala Snapshots" at "http://scala-tools.org/repo-snapshots/",
	// "simmetrics repository" at "http://maven.mse.jhu.edu/m2repository",
	"simmetrics repository" at "https://dev-iesl.cs.umass.edu/nexus/content/repositories/releases/"
)

// Project dependencies
libraryDependencies ++= Seq(
	"net.tqft" %% "toolkit-base" % "0.1.18-SNAPSHOT",
	"net.tqft" %% "toolkit-collections" % "0.1.18-SNAPSHOT",
	"net.tqft" %% "toolkit-amazon" % "0.1.18-SNAPSHOT",
	"net.tqft" %% "toolkit-wiki" % "0.1.19-SNAPSHOT",
	"net.tqft" %% "citation-search" % "0.2-SNAPSHOT",
	"com.ibm.icu" % "icu4j" % "55.1",
	"rome" % "rome" % "1.0",
	"org.apache.httpcomponents" % "httpclient" % "4.4.1",
	"org.apache.commons" % "commons-compress" % "1.5",
	"commons-io" % "commons-io" % "2.4",
	"com.google.code.findbugs" % "jsr305" % "2.0.2",
	"net.sourceforge.htmlunit" % "htmlunit" % "2.16",
	"org.seleniumhq.selenium" % "selenium-support" % "3.4.0",
	"org.seleniumhq.selenium" % "htmlunit-driver" % "2.27",
	"org.seleniumhq.selenium" % "selenium-firefox-driver" % "3.4.0",
	"org.seleniumhq.selenium" % "selenium-chrome-driver" % "3.4.0",
	"be.roam.hue" % "hue" % "1.1",
	"org.jsoup" % "jsoup" % "1.8.2",
	"eu.medsea.mimeutil" % "mime-util" % "2.1.3",
	"mysql" % "mysql-connector-java" % "5.1.24",
	"com.typesafe.slick" %% "slick" % "3.1.1",
	"uk.ac.shef.wit" % "simmetrics" % "1.6.2",
	"org.apache.pdfbox" % "pdfbox" % "1.8.9",
	"com.github.tototoshi" %% "scala-csv" % "1.3.4",
	"org.scalaz" %% "scalaz-core" % "7.2.13",
	"io.argonaut" %% "argonaut" % "6.2"
)

// lift-json
libraryDependencies ++= {
  val liftVersion = "3.0.1" // Put the current/latest lift version here
  Seq(
    "net.liftweb" %% "lift-util" % liftVersion,
    "net.liftweb" %% "lift-json" % liftVersion,
    "net.liftweb" %% "lift-json-ext" % liftVersion
    )
}

// Test dependencies
libraryDependencies ++= Seq(
	"junit" % "junit" % "4.12" % "test",
	"org.scalatest" %% "scalatest" % "3.0.1" % "compile,test"
)

// Sometimes it's useful to see debugging out from the typer (e.g. to resolve slow compiles)
// scalacOptions += "-Ytyper-debug"

EclipseKeys.withSource := true

EclipseKeys.eclipseOutput := Some(".target")

// logLevel in Global := Level.Warn

logBuffered := false

publishTo := Some(Resolver.sftp("tqft.net", "tqft.net", "tqft.net/releases") as ("scottmorrison", new java.io.File("/Users/scott/.ssh/id_rsa")))

net.virtualvoid.sbt.graph.Plugin.graphSettings
