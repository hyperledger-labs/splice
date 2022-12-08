package com.daml.network.util

import com.auth0.client.auth.AuthAPI
import com.auth0.client.mgmt.ManagementAPI
import com.auth0.json.mgmt.users.User

class Auth0User(val id: String, val email: String, val password: String, val auth0: Auth0Util)
    extends AutoCloseable {
  override def close(): Unit = auth0.deleteUser(id)
}

class Auth0Util(
    domain: String,
    managementApiClientId: String,
    managementApiClientSecret: String,
) {
  private val auth = new AuthAPI(domain, managementApiClientId, managementApiClientSecret)
  val api = new ManagementAPI(domain, requestManagementAPIToken())

  class Auth0User(val id: String, val email: String, val password: String) extends AutoCloseable {
    override def close(): Unit = deleteUser(id)
  }

  def createUser(): Auth0User = {
    val user = new User()
    val rand = new scala.util.Random
    val password = s"${rand.alphanumeric.take(20).mkString}${rand.nextInt()}"
    val username = (new scala.util.Random).alphanumeric.take(20).mkString
    val email = s"$username@test.com"
    user.setPassword(password.toCharArray)
    user.setEmail(email)
    user.setConnection("Username-Password-Authentication")
    val id = api.users().create(user).execute().getId
    new Auth0User(id, email, password)
  }

  def deleteUser(id: String): Unit = {
    api.users.delete(id).execute()
  }

  private def requestManagementAPIToken(): String =
    auth.requestToken(s"${domain}/api/v2/").execute().getAccessToken()
}
