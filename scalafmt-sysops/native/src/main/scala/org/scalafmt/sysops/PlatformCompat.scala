package org.scalafmt.sysops

import scala.concurrent.ExecutionContext
import scala.scalanative.runtime.Platform

private[scalafmt] object PlatformCompat {
  def isScalaNative = true
  def prepareCommand(cmd: Seq[String]) =
    if (Platform.isWindows()) cmd.map(arg => s""""$arg"""") else cmd
  def isNativeOnWindows = Platform.isWindows()

  implicit def executionContext: ExecutionContext = ExecutionContext.global

}
