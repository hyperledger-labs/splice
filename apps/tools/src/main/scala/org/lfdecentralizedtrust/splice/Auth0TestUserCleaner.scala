// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.tools

import com.auth0.client.mgmt.filter.UserFilter
import com.auth0.exception.Auth0Exception

import java.time.{ZoneId, ZonedDateTime}
import java.time.format.DateTimeFormatter
import org.lfdecentralizedtrust.splice.util.Auth0Util
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext

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

  def removeTestUsers(util: Auth0Util)(implicit tc: TraceContext): Unit = {
    // The default value, see https://auth0.com/docs/manage-users/user-search/view-search-results-by-page#limitation
    val usersPerPage: Int = 50

    val userCount = retryAuth0Calls(
      util.listUsers(unusedTestUserFilter.withPage(0, usersPerPage))
    ).total
    val pageCount = (userCount / usersPerPage) + 1
    println(s"Found ${userCount} stale test users")
    val staleUsers =
      Range(0, pageCount).foldLeft(List.empty[com.auth0.json.mgmt.users.User])((users, page) => {
        println(s"Fetching page ${page}...")
        users ++ retryAuth0Calls(
          util.listUsers(unusedTestUserFilter.withPage(page, usersPerPage))
        ).users
      })
    staleUsers.foreach(u => {
      val uid = u.getId()
      val name = u.getName()
      val createdAt = u.getCreatedAt()

      println(s"Deleting user $name created on $createdAt with id $uid")
      retryAuth0Calls(util.deleteUser(uid))
    })

    // Sometimes the call to delete a user succeeds, but the user won't disappear from the list of users.
    val remainingUsers = retryAuth0Calls(
      util.listUsers(unusedTestUserFilter.withPage(0, 10))
    )
    if (remainingUsers.total > 0) {
      println(
        s"Note: ${remainingUsers.total} users are still listed after being deleted. Examples:\n  ${remainingUsers.users
            .map(_.getName())
            .mkString("\n  ")}"
      )
    }
    println("Success: all stale users deleted")
  }

  // Super simple retrier
  def retryAuth0Calls[T](f: => T, retryCount: Int = 10)(implicit traceContext: TraceContext): T = {
    if (retryCount <= 0) {
      throw new Error("Auth0 retry count exhausted")
    }

    try {
      f
    } catch {
      case auth0Exception: Auth0Exception => {
        println(
          s"Auth0 exception raised, triggering retry: $retryCount attempts remaining...\n  ${auth0Exception.getMessage}"
        )
        retryAuth0Calls(f, retryCount - 1)
      }
      case ex: Throwable => throw ex // throw anything else
    }
  }

  def run(
      domain: String,
      clientId: String,
      clientSecret: String,
      loggerFactory: NamedLoggerFactory,
  )(implicit tc: TraceContext) = {
    val auth0Util = new Auth0Util(
      domain,
      clientId,
      clientSecret,
      loggerFactory,
      new Auth0Util.Auth0Retry {
        override def retryAuth0CallsForTests[T](f: => T)(implicit
            tc: TraceContext
        ): T =
          retryAuth0Calls(f)
      },
    )

    println(s"Deleting auth0 test users older than $maxUserAge day(s)...")
    removeTestUsers(auth0Util)
  }
}
