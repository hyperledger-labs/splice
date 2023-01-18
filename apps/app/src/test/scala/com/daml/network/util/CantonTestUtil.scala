package com.daml.network.util

import scala.util.Using.Releasable
import java.lang.Process

/** Utilities for starting Canton within our tests.
  * For most tests, we use the Canton environment started up by `./start-canton.sh` but
  * for testing the runbook we spin up our own Canton instance.
  */
object CantonProcessTestUtil {
  final case class CantonProcess(process: Process) {
    def destroyAndWait(): Unit = {
      process.destroy()
      process.waitFor()
    }
  }

  object CantonProcess {
    implicit val releasable: Releasable[CantonProcess] = new Releasable[CantonProcess] {
      override def release(process: CantonProcess) = process.destroyAndWait()
    }
  }
}

trait CantonProcessTestUtil {
  import CantonProcessTestUtil.CantonProcess

  private val defaultArgs =
    Seq(
      "canton",
      "daemon",
      "--log-file-name",
      "log/standalone-canton.log",
    )

  protected def startCanton(
      args: String*
  ): CantonProcess = {
    // We use the Java process management APIs here since they
    // support proper stream inheritance. Scala implements that by
    // copying streams which can result in some stream closed exceptions
    // if the child process dies while the reader is blocked on a read.
    // See https://github.com/scala/scala/blob/1efd473aae819c8ddd2dc0656a4259c89bf03312/src/library/scala/sys/process/BasicIO.scala#LL246C39-L246C52
    // for the code in the scala stdlib that does the copying.
    val builder = new ProcessBuilder(
      (defaultArgs ++ args): _*
    )
    // Clear classpath to avoid issues with multiple logging libs being picked up.
    builder
      .environment()
      .put(
        "CLASSPATH",
        "",
      )
    builder.inheritIO()
    CantonProcess(
      builder.start()
    )
  }
}
