package com.daml.network.util

import com.auth0.client.mgmt.ManagementAPI
import com.auth0.json.mgmt.users.{User as Auth0User}

class Auth0Util(
    domain: String,
    token: String,
) {

  val api = new ManagementAPI(domain, token)

  class User(val id: String, val email: String, val password: String) extends AutoCloseable {
    override def close(): Unit = deleteUser(id)
  }

  def createUser(): User = {
    val user = new Auth0User()
    val rand = new scala.util.Random
    val password = s"${rand.alphanumeric.take(20).mkString}${rand.nextInt()}"
    val username = (new scala.util.Random).alphanumeric.take(20).mkString
    val email = s"$username@test.com"
    user.setPassword(password.toCharArray)
    user.setEmail(email)
    user.setConnection("Username-Password-Authentication")
    val id = api.users().create(user).execute().getId
    new User(id, email, password)
  }

  private def deleteUser(id: String): Unit = {
    api.users.delete(id).execute()
  }
}
