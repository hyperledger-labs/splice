package org.lfdecentralizedtrust.splice.unit.http

import com.digitalasset.canton.concurrent.Threading

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.blocking
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.sys.process.{Process, ProcessLogger}
import scala.concurrent.duration.*

/** Support trait to start and manage a tinyproxy HTTP proxy for use in tests.
  */
trait TinyProxySupport {
  object HttpProxy {

    /** Create and start a tinyproxy process with a basic configuration.
      * A temporary file is created to hold the configuration, and deleted when the proxy is stopped.
      * The stdout is scraped to determine when the proxy is ready.
      * @param maxStartupTime The maximum time to wait for the proxy to start.
      * @param auth Optional basic auth credentials (username, password) that will be required by the proxy.
      * @return the HttpProxy instance representing the started proxy.
      */
    def apply(maxStartupTime: Duration, auth: Option[(String, String)]): HttpProxy = {
      val configFile = File.createTempFile("tinyproxy", ".conf")
      val proxyPort = 3128
      createConfig(configFile, proxyPort, auth)
      val proxyProcess = new ProxyProcess(configFile)
      @tailrec
      def waitUntilStarted(maxWait: Long): Unit = {
        if (maxWait > 0) {
          val wait = 50L
          Threading.sleep(wait)
          if (
            !proxyProcess.stdOutLines.exists(
              _.contains("Starting main loop. Accepting connections.")
            )
          ) {
            waitUntilStarted(maxWait - wait)
          }
        } else
          throw new RuntimeException(
            "Timed out waiting for tinyproxy to start, stderr:\n" + proxyProcess.stdErrLines
              .mkString("\n")
          )
      }
      waitUntilStarted(maxStartupTime.toMillis)
      TinyProxy(proxyProcess, proxyPort, configFile)
    }
    // creates a basic config file that allows localhost to connect, and optionally sets required basic auth
    private def createConfig(
        configFile: File,
        proxyPort: Int,
        auth: Option[(String, String)],
    ): Unit = {
      val config =
        s"""
           |Port ${proxyPort}
           |Listen 0.0.0.0
           |Timeout 600
           |Allow 127.0.0.1
           |Allow localhost
           |${auth.map { case (u, p) => s"BasicAuth $u $p" }.getOrElse("")}
           |""".stripMargin
      Files.write(configFile.toPath, config.getBytes(StandardCharsets.UTF_8))
    }
    // Represents a running tinyproxy process, with methods to check its status and stop it
    case class TinyProxy(process: ProxyProcess, port: Int, configFile: File) extends HttpProxy {

      def hasNoErrors: Boolean = process.hasNoErrors
      // Verifies that tinyproxy logged that it handled a CONNECT request to proxy to the given server port on localhost
      def proxiedConnectRequest(host: String, serverPort: Int): Boolean = {
        val connectPattern = s"^CONNECT.*$host:$serverPort.*".r
        List(
          process.stdOutLines.exists { line =>
            line match {
              case connectPattern() => true
              case _ => false
            }
          },
          process.stdOutLines.exists(_.contains(s"opening connection to $host:$serverPort")),
          process.stdOutLines.exists(
            _.contains(s"opensock: getaddrinfo returned for $host:$serverPort")
          ),
        ).forall(_ == true)
      }
      def stop(): Unit = {
        process.destroy()
        configFile.delete()
      }
    }
  }

  // Runs the tinyproxy process with the given config file, capturing stdout and stderr
  class ProxyProcess(configFile: File) {
    private val cmd = s"tinyproxy -d -c ${configFile.getAbsolutePath}"
    private val processBuilder = Process(cmd)
    private val stdout = mutable.Buffer[String]()
    private val stderr = mutable.Buffer[String]()
    private val processLogger = ProcessLogger(
      out =>
        blocking {
          synchronized {
            stdout.append(out)
          }
        },
      err =>
        blocking {
          synchronized {
            stderr.append(err)
          }
        },
    )
    private val process = processBuilder.run(processLogger)
    def stdOutLines: Seq[String] = blocking { synchronized { stdout.toSeq } }
    def stdErrLines: Seq[String] = blocking { synchronized { stderr.toSeq } }
    def hasNoErrors: Boolean = stdErrLines.isEmpty
    def destroy(): Unit = process.destroy()
  }

  /** A running tinyproxy HTTP proxy instance.
    */
  trait HttpProxy {

    /** The port the proxy is listening on */
    def port: Int

    /** The tinyproxy process and its stdout and sterr output */
    def process: ProxyProcess

    /** Whether the proxy has logged any errors to stderr */
    def hasNoErrors: Boolean

    /** Whether the proxy has logged handling a CONNECT request to the given host and server port */
    def proxiedConnectRequest(host: String, serverPort: Int): Boolean

    /** Stop the proxy and clean up any resources */
    def stop(): Unit
  }

  /** Helper method to create a tinyproxy instance for the duration of the test code.
    * @param auth Optional basic auth credentials (username, password) required by the proxy.
    * @param maxStartupTime The maximum time to wait for the proxy to start.
    * @param testCode The test code to run with the started proxy.
    * @tparam T the result of the test code.
    * @return the result of the test code.
    */
  def withProxy[T](
      auth: Option[(String, String)] = None,
      maxStartupTime: FiniteDuration = 5.seconds,
  )(testCode: HttpProxy => T): T = {
    val proxy = HttpProxy(maxStartupTime, auth)
    try {
      testCode(proxy)
    } finally {
      proxy.stop()
    }
  }
}
