/*
 * Copyright 2014 YMC. See LICENSE for details.
 */

package models

import collection.mutable
import play.api.{Configuration, Logger}
import play.api.libs.ws.{WSResponse, WS}
import play.api.Play.current
import org.apache.commons.lang.StringUtils
import models.LogFile._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.Breaks._
import java.util.regex._
import java.text.SimpleDateFormat
import globals.hBaseContext
import models.hbase.RegionServer

case class LogFile(regionServer:RegionServer) {

  def tail() = {
    val url = logFileUrl(regionServer)

    if (!logOffsets.contains(regionServer.serverName)) {
      val headValue: WSResponse = Await.result(WS.url(url).head(), logFetchTimeout.seconds)
      val logLength = headValue.header("Content-Length").get.toLong
      val offset = scala.math.max(0, logLength - initialLogLookBehindSizeInKBs * 1024)
      Logger.info("Initializing log offset to [%d] for log file at %s with content-length [%d]".format(offset, url, logLength))
      logOffsets(regionServer.serverName) = offset
    }

    var response: WSResponse = recentLogContent(url, logOffsets(regionServer.serverName))

    if(wasRotated(response)) {
      logOffsets(regionServer.serverName) = 0l
      Logger.info("Log file [%s] seems to have rotated (server returned 416), resetting offset to 0".format(url))
      response = recentLogContent(url, logOffsets(regionServer.serverName))
      if(wasRotated(response)) {
        throw new Exception("Could not load logfile from server even after resetting logOffset to 0")
      }
    }

    // Set the next offset to the base offset + the offset matching the last newline found
    logOffsets(regionServer.serverName) = logOffsets(regionServer.serverName) + offsetOfLastNewline(response.body)

    Logger.debug("Updating logfile offset to [%d] for server %s".
      format(logOffsets(regionServer.serverName), regionServer))

    response.body
  }

  def recentLogContent(url: String, offset: Long) = {
    Logger.debug("... fetching Logfile from %s with range [%d-]".format(url, offset))
    val response = Await.result(WS.url(url).withHeaders(("Range", "bytes=%d-".format(offset))).get(), logFetchTimeout.seconds)
    val statusCode = response.status
    if (!List(200, 206, 416).contains(statusCode)) {
      throw new Exception("couldn't load Compaction Metrics from URL: '" +
        url + " (statusCode was: "+statusCode+")")
    }
    response
  }

  def wasRotated(response:WSResponse):Boolean = {
    if(response.status == 416) {
      Logger.debug("Log file [%s] seems to have rotated (StatusCode = 416)")
      return true
    }

    val contentRange = response.header("Content-Range").getOrElse("")
    val rangeValue = StringUtils.substringBetween(contentRange, "bytes", "/").trim()

    if (rangeValue eq "*") {
      Logger.debug("Log file [%s] seems to have rotated (RangeValue is *)")
      return true
    }

    return false
  }

  def offsetOfLastNewline(body: String):Long = {
    val bytes: Array[Byte] = body.getBytes

    for (i <- bytes.length - 1 to  0 by -1) {
      if (bytes(i) == NEWLINE) {
        return i
      }
    }

    0
  }
}

object LogFile {

  private var logFetchTimeout: Int = 5
  private var initialLogLookBehindSizeInKBs: Int = 1024
  private var logFileUrlPattern: String = null
  private var logFilePathPattern: Pattern = null
  private var logLevelUrlPattern: String = null
  private var setLogLevelsOnStartup: Boolean = false
  private var logFileDateFormat: SimpleDateFormat = null
  private var logOffsets = mutable.Map.empty[String, Long]

  val NEWLINE = "\n".getBytes("UTF-8")(0)

  def initialize(configuration:Configuration) = {
    this.setLogLevelsOnStartup = configuration.getBoolean("logfile.set-loglevels-on-startup").getOrElse(false)
    this.logLevelUrlPattern = configuration.getString("logfile.loglevel-url-pattern").getOrElse("")
    this.logFilePathPattern = Pattern.compile( configuration.getString("logfile.path-pattern").getOrElse(""))
    this.logFileDateFormat = new java.text.SimpleDateFormat(configuration.getString("logfile.date-format").getOrElse(""))
    this.logFetchTimeout = configuration.getInt("logfile.fetch-timeout-in-seconds").getOrElse(30)
    this.initialLogLookBehindSizeInKBs = configuration.getInt("logfile.initial-look-behind-size-in-kb").getOrElse(1024)
  }

  def discoverLogFileUrlPattern = {
    var logFilePattern: String  = null
    breakable {
      globals.hBaseContext.hBase.eachRegionServer { regionServer =>
        val url = logRootUrl(regionServer)
        val response = Await.result(WS.url(url).get(), logFetchTimeout.seconds)
        val logFileMatcher = logFilePathPattern.matcher(response.body)

        if (logFileMatcher.find()) {
          val path = logFileMatcher.group(1)
          // We assume that all region servers use the same pattern so once we've got the pattern for one of them,
          // we stop
          Logger.info("Found path matching logfile.path-pattern: %s".format(path))
          logFilePattern = (url + path).replaceAll(regionServer.hostName, "%hostname%")
            .replaceAll(regionServer.infoPort.toString, "%infoport%")
            .replaceAll(regionServer.hostName.split("\\.")(0), "%hostname-without-domain%")
          break()
        }
      }
    }

    logFilePattern
  }

  def init():Boolean = {
    if (setLogLevelsOnStartup) {
      Logger.info("setting Loglevels for the Regionservers")
      hBaseContext.hBase.eachRegionServer { regionServer =>
        val url = logLevelUrl(regionServer)
        val response = Await.result(WS.url(url).get(), logFetchTimeout.seconds)
        if (response.status != 200) {
          throw new Exception("couldn't set log-level with URL: " + url);
        } else {
          Logger.debug("... Loglevel set for server %s".format(regionServer))
        }
      }
    }

    logFileUrlPattern = discoverLogFileUrlPattern
    if(logFileUrlPattern != null) {
      Logger.info("Discovered log file url pattern: [%s]".format(logFileUrlPattern))
      return true
    } else {
      Logger.warn("Could not discover log file url pattern using pathPattern: [%s]".format(logFilePathPattern))
      return false
    }
  }

  def all(): List[LogFile] =
    hBaseContext.hBase.eachRegionServer { regionServer =>
      LogFile(regionServer)
    }

  def dateFormat() = logFileDateFormat

  def forServer(regionServer: RegionServer) = LogFile(regionServer)

  def logFileUrl(regionServer: RegionServer) = regionServer.infoUrl(logFileUrlPattern)

  def logLevelUrl(regionServer: RegionServer) = regionServer.infoUrl(logLevelUrlPattern)

  def logRootUrl(regionServer: RegionServer) = regionServer.infoUrl("http://%hostname%:%infoport%/logs/")
}
