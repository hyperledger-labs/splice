package org.lfdecentralizedtrust.splice.util

import org.postgresql.ds.PGSimpleDataSource
import org.scalatest.{BeforeAndAfterEach, Suite}
import org.slf4j.LoggerFactory

import java.sql.Statement
import scala.util.{Try, Using}
import cats.syntax.traverse.*

trait PostgresAroundEach extends BeforeAndAfterEach {
  self: Suite =>

  def usesDbs: Seq[String]
  private val logger = LoggerFactory.getLogger(getClass)

  private val hostName = env("POSTGRES_HOST")
  private val port = sys.env.getOrElse("POSTGRES_PORT", "5432").toInt
  private val userName = env("POSTGRES_USER")
  private val password = env("POSTGRES_PASSWORD")
  private val baseDatabase = "postgres"

  def baseDbUrlWithoutCredentials: String =
    s"jdbc:postgresql://$hostName:$port/$baseDatabase"

  def baseDbUrl: String =
    s"$baseDbUrlWithoutCredentials?user=$userName&password=$password"

  private val cantonUser = "canton"

  override protected def beforeEach(): Unit = {
    logger.debug(s"Using postgres on ${hostName}:${port}")
    dropAll()
    usesDbs.foreach(createDb(_))
    super.beforeEach()
  }

  override protected def afterEach(): Unit = {
    super.afterEach()
    dropAll()
  }

  private def createDb(dbName: String) = {
    logger.debug(s"Creating database $dbName")
    executeAdminStatement() { statement =>
      statement.execute(s"CREATE DATABASE \"$dbName\"")
      statement.execute(s"GRANT ALL PRIVILEGES ON DATABASE $dbName TO $cantonUser")
    }
  }

  private def dropAll() = {
    usesDbs
      .traverse(dbName => Try(dropDb(dbName)))
      .fold(
        err => logger.warn(s"Failed to drop database: $err"),
        _ => logger.debug("Dropped all databases"),
      )
  }

  private def dropDb(dbName: String) = {
    executeAdminStatement() { statement =>
      statement.execute(s"DROP DATABASE IF EXISTS \"$dbName\" WITH (FORCE)")
    }
    logger.debug(s"Dropped database $dbName")
  }

  private def executeAdminStatement[T]()(body: Statement => T): T = {
    Using.resource {
      val dataSource = new PGSimpleDataSource()
      dataSource.setUrl(baseDbUrl)
      dataSource.getConnection
    } { connection =>
      Using.resource(connection.createStatement())(body)
    }
  }

  private def env(name: String): String =
    sys.env.getOrElse(name, sys.error(s"Environment variable not set [$name]"))
}
