package org.lfdecentralizedtrust.splice.util

import com.auth0.client.auth.AuthAPI
import com.auth0.client.mgmt.ManagementAPI
import com.auth0.client.mgmt.filter.UserFilter
import com.auth0.exception.Auth0Exception
import com.auth0.json.mgmt.users.User
import com.digitalasset.canton.BaseTest

import scala.jdk.CollectionConverters.*
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.typesafe.scalalogging.Logger
import net.jodah.failsafe.FailsafeException
import org.lfdecentralizedtrust.splice.util.Auth0Util.Auth0Retry

import scala.collection.mutable
import scala.util.control.NonFatal

class Auth0User(val id: String, val email: String, val password: String, val auth0: Auth0Util)
    extends AutoCloseable {
  override def close(): Unit = auth0.deleteUser(id)
}

case class Auth0UserPage(
    users: List[User],
    total: Int,
)

class Auth0Util(
    val domain: String,
    managementApiClientId: String,
    managementApiClientSecret: String,
    override val loggerFactory: NamedLoggerFactory,
    retry: Auth0Retry,
) extends NamedLogging {
  private val auth = new AuthAPI(domain, managementApiClientId, managementApiClientSecret)
  val api = new ManagementAPI(domain, requestManagementAPIToken())

  def createUser()(implicit tc: TraceContext): Auth0User = {
    // Note that a "user already exists" error may be caused by our retries if we retry only the create-user call,
    // so in a retry we also choose a new username, email address, etc.
    retry.retryAuth0CallsForTests {
      val user = new User()
      val rand = new scala.util.Random
      // The randomly generated password is prefixed with enough constants to prevent unfortunate weak passwords that fail auth0 checks
      val password = s"aB3${rand.alphanumeric.take(20).mkString}${rand.nextInt()}"
      val username = (new scala.util.Random).alphanumeric.take(20).mkString
      val email = s"$username@canton-network-test.com"
      user.setPassword(password.toCharArray)
      user.setEmail(email)
      user.setVerifyEmail(false) // avoid auth0 trying to send mails
      user.setConnection("Username-Password-Authentication")
      // Auth0 management API calls are rate limited, with limits much lower than
      // the rate limits for the auth API calls.
      // Here we simply assume a generic rate limit of 2 calls per second and
      // wait before each management API call
      Threading.sleep(500)
      logger.debug(s"Creating user with username $username email $email and password $password")
      val id = api.users().create(user).execute().getId
      logger.debug(s"Created user ${email} with password ${password} (id: ${id})")
      new Auth0User(id, email, password, this)
    }
  }

  def deleteUser(id: String): Unit = {
    // Called from AutoCloseable.close, which doesn't propagate the trace context
    implicit val traceContext: TraceContext = TraceContext.empty
    logger.debug(s"Deleting user with id: ${id}")
    executeManagementApiRequest(api.users.delete(id))
  }

  def listUsers(filter: UserFilter)(implicit tc: TraceContext): Auth0UserPage = {
    val page = executeManagementApiRequest(api.users().list(filter))

    logger.info(s"Found ${page.getTotal} total users, returning limit of ${page.getLimit()}")
    Auth0UserPage(
      page.getItems().asScala.toList,
      page.getTotal,
    )
  }

  private def requestManagementAPIToken(): String = {
    auth.requestToken(s"${domain}/api/v2/").execute().getAccessToken()
  }

  private[this] def executeManagementApiRequest[T](
      req: com.auth0.net.Request[T]
  )(implicit traceContext: TraceContext) =
    retry.retryAuth0CallsForTests {
      // Auth0 management API calls are rate limited, with limits much lower than
      // the rate limits for the auth API calls.
      // Here we simply assume a generic rate limit of 2 calls per second and
      // wait before each management API call
      Threading.sleep(500)
      req.execute()
    }
}

object Auth0Util {

  trait Auth0Retry {
    def retryAuth0CallsForTests[T](f: => T)(implicit
        traceContext: TraceContext
    ): T
  }

  trait WithAuth0Support {
    this: BaseTest =>

    def auth0UtilFromEnvVars(
        tenant: String
    ): Auth0Util = {
      val (mgmtPrefix, domainPrefix) = tenant match {
        // Used for preflight checks
        case "dev" => ("AUTH0_CN", "SPLICE_OAUTH_DEV")
        // Used for sv preflight checks
        case "sv" => ("AUTH0_SV", "SPLICE_OAUTH_SV_TEST")
        // Used for validator preflight checks
        case "validator" => ("AUTH0_VALIDATOR", "SPLICE_OAUTH_VALIDATOR_TEST")
        // Used locally
        case "test" => ("AUTH0_TESTS", "SPLICE_OAUTH_TEST")
        case _ => fail(s"Invalid tenant value: $tenant")
      }
      val domain = s"https://${readMandatoryEnvVar(s"${domainPrefix}_AUTHORITY")}";
      val clientId = readMandatoryEnvVar(s"${mgmtPrefix}_MANAGEMENT_API_CLIENT_ID");
      val clientSecret = readMandatoryEnvVar(s"${mgmtPrefix}_MANAGEMENT_API_CLIENT_SECRET");

      new Auth0Util(
        domain,
        clientId,
        clientSecret,
        loggerFactory,
        new Auth0Retry {
          def handleExceptions[T](e: Throwable)(implicit
              traceContext: TraceContext
          ): T =
            e match {
              case auth0Exception: Auth0Exception => {
                logger.debug("Auth0 exception raised, triggering retry...", auth0Exception)
                fail(auth0Exception)
              }
              case ioException: java.io.IOException => {
                logger.debug("IOException raised, triggering retry...", ioException)
                fail(ioException)
              }
              case ex: Throwable => throw ex // throw anything else
            }

          override def retryAuth0CallsForTests[T](
              f: => T
          )(implicit traceContext: TraceContext): T = {
            eventually() {
              try {
                f
              } catch {
                // the sync client wraps the exceptions because why not
                case failsafe: FailsafeException => handleExceptions(failsafe.getCause)
                case NonFatal(cause) => handleExceptions(cause)
              }
            }
          }
        },
      )
    }

    private def readMandatoryEnvVar(name: String): String = {
      sys.env.get(name) match {
        case None => fail(s"Environment variable $name must be set")
        case Some(s) if s.isEmpty => fail(s"Environment variable $name must be non-empty")
        case Some(s) => s
      }
    }

  }

  def getAuth0ClientCredential(
      clientId: String,
      audience: String,
      auth0Domain: String,
  )(implicit logger: Logger): String = {

    val outLines = mutable.Buffer[String]()
    val errLines = mutable.Buffer[String]()
    val outProcessor =
      scala.sys.process.ProcessLogger(line => outLines.append(line), line => errLines.append(line))
    try {
      val ret = scala.sys.process
        .Process(
          Seq(
            "build-tools/get-auth0-token.py",
            "--client-id",
            clientId,
            "--audience",
            audience,
            "--auth0-domain",
            auth0Domain,
          )
        )
        .!(outProcessor)
      if (ret != 0) {
        // Stderr contains all actual output, stdout should consist of only the token if successful
        logger.error("Failed to run get-auth0-token.py. Dumping output.")
        errLines.foreach(logger.error(_))
        throw new RuntimeException("get-auth0-token.py failed")
      }
    } catch {
      case NonFatal(ex) =>
        // Stderr contains all actual output, stdout should consist of only the token if successful
        logger.error("Failed to run get-auth0-token.py. Dumping output.", ex)
        errLines.foreach(logger.error(_))
        throw new RuntimeException("get-auth0-token.py failed.", ex)
    }

    outLines.head
  }

}
