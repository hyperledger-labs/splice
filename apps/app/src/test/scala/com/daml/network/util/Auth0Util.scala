package com.daml.network.util

import com.auth0.client.auth.AuthAPI
import com.auth0.client.mgmt.ManagementAPI
import com.auth0.client.mgmt.filter.UserFilter
import com.auth0.json.mgmt.users.User

import scala.jdk.CollectionConverters.*
import com.daml.network.auth.OAuthApi.TokenResponse
import com.daml.network.auth.AuthToken
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

class Auth0User(val id: String, val email: String, val password: String, val auth0: Auth0Util)
    extends AutoCloseable {
  override def close(): Unit = auth0.deleteUser(id)
}

case class Auth0UserPage(
    users: List[User],
    total: Int,
)

class Auth0Util(
    domain: String,
    managementApiClientId: String,
    managementApiClientSecret: String,
    override val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {
  private val auth = new AuthAPI(domain, managementApiClientId, managementApiClientSecret)
  val api = new ManagementAPI(domain, requestManagementAPIToken())

  def createUser(): Auth0User = {
    val user = new User()
    val rand = new scala.util.Random
    val password = s"${rand.alphanumeric.take(20).mkString}${rand.nextInt()}"
    val username = (new scala.util.Random).alphanumeric.take(20).mkString
    val email = s"$username@canton-network-test.com"
    user.setPassword(password.toCharArray)
    user.setEmail(email)
    user.setVerifyEmail(false) // avoid auth0 trying to send mails
    user.setConnection("Username-Password-Authentication")
    val id = executeManagementApiRequest(api.users().create(user)).getId
    new Auth0User(id, email, password, this)
  }

  def deleteUser(id: String): Unit = {
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

  def getToken(clientId: String, audience: String): AuthToken = {
    val client = executeManagementApiRequest(api.clients().get(clientId))
    val clientSecret = client.getClientSecret()
    val appApi = new AuthAPI(domain, clientId, clientSecret)
    val response = appApi.requestToken(audience).execute()

    AuthToken(TokenResponse(response.getAccessToken(), response.getExpiresIn()))
  }

  private def requestManagementAPIToken(): String = {
    auth.requestToken(s"${domain}/api/v2/").execute().getAccessToken()
  }

  private def executeManagementApiRequest[T](req: com.auth0.net.Request[T]) = {
    // Auth0 management API calls are rate limited, with limits much lower than
    // the rate limits for the auth API calls.
    // Here we simply assume a generic rate limit of 2 calls per second and
    // wait before each management API call
    Threading.sleep(500)
    req.execute()
  }
}
