package com.daml.network.integration.plugins

import cats.syntax.parallel.*
import com.daml.network.config.{CNDbConfig, CNNodeConfig, CNNodeConfigTransforms}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.digitalasset.canton.TestMetrics
import com.digitalasset.canton.concurrent.{
  ExecutionContextIdlenessExecutorService,
  ExecutorServiceExtensions,
  Threading,
}
import com.digitalasset.canton.config.*
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import com.digitalasset.canton.lifecycle.{FlagCloseable, HasCloseContext, Lifecycle}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.store.db.{DbStorageSetup, PostgresDbStorageSetup}
import com.digitalasset.canton.tracing.NoTracing
import com.digitalasset.canton.util.FutureInstances.*
import com.typesafe.config.ConfigFactory
import monocle.macros.syntax.lens.*

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Random

/** Copy of Canton's UsePostgres, adapted for CN apps.
  *
  * Plugin to provide a postgres backend to a [[com.digitalasset.canton.integration.BaseIntegrationTest]] instance.
  *
  * By default, tests will run against a dockerized db. So you need to nothing except for installing docker.
  *
  * To get higher performance (in particular on Mac OSX), you may want to run tests against a non-containerized postgres db.
  * To do so, export the following environment variables:
  * <pre>
  * CI=1
  * POSTGRES_USER=test # changing the username is not recommended, see data continuity tests documentation in contributing.md
  * POSTGRES_PASSWORD=supersafe
  * POSTGRES_DB=postgres
  * </pre>
  *
  * Please note that you need to create in this case two databases: $POSTGRES_DB AND "${POSTGRES_DB}_dev"
  *
  * On the database, you need to create the user as follows:
  *
  * <pre>
  * $ psql postgres
  * postgres=# create user test with password 'supersafe';
  * CREATE ROLE
  * postgres=# ALTER USER test CREATEDB;
  * ALTER ROLE
  * postgres=# exit
  * </pre>
  *
  * @param customDbNames optionally defines a mapping from identifier to db name (String => String) and a suffix
  *    to add to the db name
  */
class UsePostgres(
    protected val loggerFactory: NamedLoggerFactory,
    customDbNames: Option[(String => String, String)] = None,
    customMaxConnectionsByNode: Option[String => Option[Int]] = None,
) extends EnvironmentSetupPlugin[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment]
    with FlagCloseable
    with HasCloseContext
    with NoTracing
    with TestMetrics {
  private lazy val dbSetupExecutorService: ExecutionContextIdlenessExecutorService =
    Threading.newExecutionContext(
      loggerFactory.threadName + "-db-execution-context",
      noTracingLogger,
      executorServiceMetrics,
    )

  private[plugins] def dbSetupExecutionContext: ExecutionContext = dbSetupExecutorService

  // Make sure that each environment and its database names are unique by generating a random prefix
  // Short prefix due to postgres database name limit of 63 bytes
  private lazy val dbPrefix = "d" + Random.alphanumeric.map(_.toLower).take(7).mkString

  lazy val applicationName: String = loggerFactory.name

  lazy val applicationNameLedgerApiServer: String = applicationName + "-ledger-api-server"

  def getOrGenerateDbName(nodeName: String): String = customDbNames match {
    case Some((dbNames, suffix)) => dbNames(nodeName) + suffix
    case None => s"${dbPrefix}_$nodeName"
  }

  // we have to keep the storage instance alive for the duration of the test as this could be
  // managing an external docker container and other resources
  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Null"))
  private[integration] var dbSetup: PostgresDbStorageSetup = _

  override def beforeTests(): Unit = {
    dbSetup = DbStorageSetup.postgres(loggerFactory)(dbSetupExecutionContext)
  }

  override def onClosed(): Unit = {
    Lifecycle.close(
      dbSetup,
      ExecutorServiceExtensions(dbSetupExecutorService)(logger, DefaultProcessingTimeouts.testing),
    )(logger)
  }

  override def afterTests(): Unit = {
    close()
  }

  def generateDbConfig(
      name: String,
      baseParameters: DbParametersConfig,
  ): CNDbConfig = {
    val dbName = getOrGenerateDbName(name)

    val basicConfigWithDbName =
      dbSetup.basicConfig.copy(dbName = dbName, connectionPoolEnabled = true)

    val configWithApplicationName =
      ConfigFactory
        .parseString(s"properties.applicationName = $applicationName")
        .withFallback(basicConfigWithDbName.toPostgresConfig)

    val dbConfigCanton = CNDbConfig.Postgres(
      configWithApplicationName,
      baseParameters,
    )

    customMaxConnectionsByNode match {
      case Some(maxConnectionsByNode) =>
        dbConfigCanton.focus(_.parameters.maxConnections).replace(maxConnectionsByNode(name))
      case None => dbConfigCanton
    }
  }

  override def beforeEnvironmentCreated(config: CNNodeConfig): CNNodeConfig = {
    implicit val ec: ExecutionContext = dbSetupExecutionContext

    // Note: CNNodeConfigTransforms performs transformations sequentially
    val nodesWithPersistence = scala.collection.mutable.ListBuffer.empty[String]
    val storageChange = CNNodeConfigTransforms.modifyAllCNStorageConfigs { (name, config) =>
      assert(
        !nodesWithPersistence.contains(name),
        "Node names must be unique, otherwise two app backends would write to the same database",
      )
      nodesWithPersistence.addOne(name)
      generateDbConfig(name, config.parameters)
    }
    val configWithPersistence = storageChange(config)

    val allDbNames = nodesWithPersistence.toSeq.map(getOrGenerateDbName)
    logger.debug(s"Databases for config ${config.name}: ${allDbNames.mkString(", ")} ")
    Await.result(
      allDbNames.parTraverse_(dbName => recreateDatabase(dbName)),
      config.parameters.timeouts.processing.io.duration,
    )

    configWithPersistence
  }

  override def afterEnvironmentDestroyed(config: CNNodeConfig): Unit = {
    val drops = dropDatabases()
    Await.result(drops, config.parameters.timeouts.processing.io.duration)
  }

  def dropDatabases(): Future[Unit] = {
    val dbNames = Seq(
      getOrGenerateDbName("directory_app")
    )
    val storage = dbSetup.storage
    import storage.api.*
    storage.update_(
      DBIO.seq(dbNames.map(db => sqlu""" drop database "#$db" """): _*),
      "dropDatabases",
    )
  }

  private def recreateDatabase(dbName: String): Future[Unit] = {
    val storage = dbSetup.storage
    import storage.api.*

    storage.update_(
      DBIO.seq(
        sqlu"""drop database if exists "#$dbName" """,
        sqlu"""create database "#$dbName" """,
      ),
      operationName = "Recreate databases",
    )
  }

  override protected def timeouts: ProcessingTimeout = DefaultProcessingTimeouts.testing
}
