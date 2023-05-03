package com.daml.network.tools

import com.auth0.client.mgmt.filter.UserFilter
import com.auth0.exception.Auth0Exception
import java.time.{ZonedDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import java.time.Duration

import com.daml.network.util.Auth0Util

object Auth0TestUserCleaner {
  val maxUserAge = 2L

  def twoDaysAgo: String = {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    formatter format ZonedDateTime.now(ZoneId.of("UTC")).minusDays(maxUserAge)
  }

  def unusedTestUserFilter = {
    // Filter + query according to auth0 documentation: https://auth0.com/docs/manage-users/user-search/user-search-query-syntax
    val nameQuery = "name:*@canton-network-test.com"
    val timeQuery = s"created_at:[* TO $twoDaysAgo]"
    val query = s"$nameQuery AND $timeQuery"

    new UserFilter()
      .withQuery(query)
      .withTotals(true)
  }

  def removeTestUsers(util: Auth0Util): Unit = {
    // set up a global timeout in case there are silent issues with user deletion that prevent the user.length from ever reaching 0
    val startTime = ZonedDateTime.now();
    val timeout = 30; // seconds

    def checkTimeout() = {
      if (Duration.between(startTime, ZonedDateTime.now()).getSeconds() > timeout) {
        throw new Error(s"User deletion loop timed out after $timeout seconds, bailing...")
      }
    }

    // auth0's list users endpoint is paginated, so we need to loop until there are no more results
    def processPage(): Unit = {
      val users = retryAuth0Calls(util.listUsers(unusedTestUserFilter))

      if (users.length > 0) {
        println(s"Found ${users.length} remaining stale test users, deleting...")

        users.foreach(u => {
          val uid = u.getId()
          val name = u.getName()
          val createdAt = u.getCreatedAt()

          println(s"Deleting user $name created on $createdAt with id $uid")
          retryAuth0Calls(util.deleteUser(uid))
        })

        checkTimeout()
        println(s"One page done, processing next page...")
        Thread.sleep(1000) // sleep for Auth0 API rate-limiting before continuing

        processPage();
      } else {
        println(s"Success: 0 stale test users remaining")
      }
    }

    processPage()
  }

  // Super simple retrier
  def retryAuth0Calls[T](f: => T, retryCount: Int = 10): T = {
    if (retryCount <= 0) {
      throw new Error("Auth0 retry count exhausted")
    }

    try {
      f
    } catch {
      case auth0Exception: Auth0Exception => {
        println(
          s"Auth0 exception raised, triggering retry: $retryCount attempts remaining..."
        )
        Thread.sleep(1000)
        retryAuth0Calls(f, retryCount - 1)
      }
      case ex: Throwable => throw ex // throw anything else
    }
  }

  def run() = {
    val domain = "https://canton-network-dev.us.auth0.com"
    val clientId = Tools.readMandatoryEnvVar("AUTH0_MANAGEMENT_API_CLIENT_ID");
    val clientSecret = Tools.readMandatoryEnvVar("AUTH0_MANAGEMENT_API_CLIENT_SECRET");

    val auth0Util = retryAuth0Calls(new Auth0Util(domain, clientId, clientSecret))

    println(s"Deleting auth0 test users older than $maxUserAge day(s)...")
    removeTestUsers(auth0Util)
  }
}
