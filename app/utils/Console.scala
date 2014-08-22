/*
 * Copyright 2014 YMC. See LICENSE for details.
 */

package utils

object Console {
   def startApp() = play.api.Play.start(new play.api.DefaultApplication(new java.io.File("."), classOf[play.core.StaticApplication].getClassLoader, None, play.api.Mode.Test))
   def stopApp() = play.api.Play.stop()
}
