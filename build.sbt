name := """hannibal"""

version := "0.98.0"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

includeFilter in (Assets, LessKeys.less) := "*.less"

excludeFilter in (Assets, LessKeys.less) := "_*.less"

scalaVersion := "2.11.1"

libraryDependencies ++= {
  val hadoopV = "2.4.1"
  val hbaseV = "0.98.5-hadoop2"
  Seq(
    jdbc,
    anorm,
    cache,
    ws,
    "org.apache.hadoop" % "hadoop-common" % hadoopV,
    "org.apache.hbase" % "hbase"        % hbaseV,
    "org.apache.hbase" % "hbase-common" % hbaseV,
    "org.apache.hbase" % "hbase-client" % hbaseV
  )
}
