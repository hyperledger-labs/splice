package org.lfdecentralizedtrust.splice.integration.tests

import cats.syntax.parallel.*
import com.auth0.exception.Auth0Exception
import com.daml.ledger.javaapi.data.Identifier
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.metrics.api.{HistogramInventory, MetricsContext, MetricsInfoFilter}
import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.daml.metrics.api.opentelemetry.OpenTelemetryMetricsFactory
import org.lfdecentralizedtrust.splice.auth.AuthUtil
import org.lfdecentralizedtrust.splice.config.AuthTokenSourceConfig
import org.lfdecentralizedtrust.splice.console.*
import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.plugins.{
  ResetDecentralizedNamespace,
  ResetSequencerDomainStateThreshold,
  UpdateHistorySanityCheckPlugin,
  WaitForPorts,
}
import org.lfdecentralizedtrust.splice.sv.config.{SvOnboardingConfig, SynchronizerFeesConfig}
import org.lfdecentralizedtrust.splice.util.{Auth0Util, CommonAppInstanceReferences}
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.integration.*
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.networking.grpc.GrpcError
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.telemetry.OpenTelemetryFactory
import com.digitalasset.canton.tracing.TracingConfig.Tracer
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import com.typesafe.scalalogging.LazyLogging
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer
import io.opentelemetry.sdk.metrics.internal.state.MetricStorage
import org.apache.pekko.actor.{ActorSystem, CoordinatedShutdown}
import org.apache.pekko.Done
import org.apache.pekko.http.scaladsl.Http
import org.scalactic.source
import org.scalatest.{AppendedClues, BeforeAndAfterEach}
import org.scalatest.exceptions.TestFailedException
import org.scalatest.matchers.{MatchResult, Matcher}

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.language.implicitConversions
import scala.math.BigDecimal.RoundingMode
import scala.util.{Failure, Success, Try}
import scala.util.chaining.scalaUtilChainingOps
import scala.util.control.NonFatal

/** Analogue to Canton's CommunityTests */
object SpliceTests extends LazyLogging {
  val IsCI: Boolean = sys.env.contains("CI")

  private val configuredOpenTelemetry: OpenTelemetry =
    if (IsCI) {
      logger.info("Initializing opentelemetry to expose test metrics on port 25001")
      OpenTelemetryFactory
        .initializeOpenTelemetry(
          initializeGlobalOpenTelemetry = true,
          attachReporters = sdkMeterProviderBuilder => {
            sdkMeterProviderBuilder.registerMetricReader(
              PrometheusHttpServer
                .builder()
                .setHost("localhost")
                .setPort(25001)
                .build()
            )
          },
          metricsEnabled = true,
          config = Tracer(),
          histogramConfigs = Seq.empty,
          loggerFactory = NamedLoggerFactory.root,
          cardinality = MetricStorage.DEFAULT_MAX_CARDINALITY,
          testingSupportAdhocMetrics = false,
          histogramInventory = new HistogramInventory(),
          histogramFilter = new MetricsInfoFilter(Seq.empty, Set.empty),
        )
        .tap { otel =>
          sys.addShutdownHook {
            logger.info("Shutting down opentelemetry test metrics")
            otel.close()
          }
        }
        .openTelemetry
    } else OpenTelemetry.noop()

  type SpliceTestConsoleEnvironment = TestConsoleEnvironment[EnvironmentImpl]
  type SharedSpliceEnvironment =
    SharedEnvironment[EnvironmentImpl, SpliceTestConsoleEnvironment]
  type IsolatedSpliceEnvironments =
    IsolatedEnvironments[EnvironmentImpl, SpliceTestConsoleEnvironment]

  trait IntegrationTest
      extends BaseIntegrationTest[EnvironmentImpl, SpliceTestConsoleEnvironment]
      with IsolatedSpliceEnvironments
      with TestCommon
      with LedgerApiExtensions {

    override lazy val testInfrastructureMetricsFactory: LabeledMetricsFactory = {
      new OpenTelemetryMetricsFactory(
        configuredOpenTelemetry.getMeterProvider.get("cn_tests"),
        Set.empty,
        Some(noTracingLogger.underlying),
        MetricsContext.Empty,
      )
    }

    protected def extraPortsToWaitFor: Seq[(String, Int)] = Seq.empty

    protected lazy val resetRequiredTopologyState: Boolean = true

    protected def runUpdateHistorySanityCheck: Boolean = true
    protected lazy val updateHistoryIgnoredRootCreates: Seq[Identifier] = Seq.empty
    protected lazy val updateHistoryIgnoredRootExercises: Seq[(Identifier, String)] = Seq.empty

    if (runUpdateHistorySanityCheck) {
      registerPlugin(
        new UpdateHistorySanityCheckPlugin(
          updateHistoryIgnoredRootCreates,
          updateHistoryIgnoredRootExercises,
          loggerFactory,
        )
      )
    }
    registerPlugin(new WaitForPorts(extraPortsToWaitFor))
    if (resetRequiredTopologyState) {
      registerPlugin(new ResetDecentralizedNamespace())
      // We MUST have the decentralized namespace reset before the reset of the sequencer domain state since
      // the latter expects that submitting the topology tx from only sv1 will succeed.
      registerPlugin(new ResetSequencerDomainStateThreshold())
    }

    override def environmentDefinition
        : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
      EnvironmentDefinition
        .simpleTopology1Sv(this.getClass.getSimpleName)
  }

  trait IntegrationTestWithSharedEnvironment
      extends BaseIntegrationTest[EnvironmentImpl, SpliceTestConsoleEnvironment]
      with SharedSpliceEnvironment
      with BeforeAndAfterEach
      with TestCommon
      with LedgerApiExtensions {

    protected def runUpdateHistorySanityCheck: Boolean = true
    protected lazy val updateHistoryIgnoredRootCreates: Seq[Identifier] = Seq.empty
    protected lazy val updateHistoryIgnoredRootExercises: Seq[(Identifier, String)] = Seq.empty

    if (runUpdateHistorySanityCheck) {
      registerPlugin(
        new UpdateHistorySanityCheckPlugin(
          updateHistoryIgnoredRootCreates,
          updateHistoryIgnoredRootExercises,
          loggerFactory,
        )
      )
    }

    protected val migrationId: Long = sys.env.getOrElse("MIGRATION_ID", "0").toLong

    override lazy val testInfrastructureMetricsFactory: LabeledMetricsFactory = {
      new OpenTelemetryMetricsFactory(
        configuredOpenTelemetry.getMeterProvider.get("cn_tests"),
        Set.empty,
        Some(noTracingLogger.underlying),
        MetricsContext.Empty,
      )
    }

    protected def extraPortsToWaitFor: Seq[(String, Int)] = Seq.empty

    protected lazy val resetRequiredTopologyState: Boolean = true

    registerPlugin(new WaitForPorts(extraPortsToWaitFor))
    if (resetRequiredTopologyState) {
      // We MUST have the decentralized namespace reset before the reset of the sequencer domain state since
      // the latter expects that submitting the topology tx from only sv1 will succeed.
      registerPlugin(new ResetDecentralizedNamespace())
      registerPlugin(new ResetSequencerDomainStateThreshold())
    }

    override def environmentDefinition
        : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
      EnvironmentDefinition
        .simpleTopology1Sv(this.getClass.getSimpleName)

    // We append this to configured Daml user names for isolation across test cases.
    @SuppressWarnings(Array("org.wartremover.warts.Var"))
    @volatile
    private var testCaseId: Int = 0

    override def beforeEach(): Unit = {
      logger.info(s"Starting test case $testCaseId")
      super.beforeEach()
    }

    override def testFinished(testName: String, env: SpliceTestConsoleEnvironment): Unit = {
      testCaseId += 1
      super.testFinished(testName, env)
    }

    // make `aliceWallet` etc. use updated usernames
    override def uwc(name: String)(implicit
        env: SpliceTestConsoleEnvironment
    ): WalletAppClientReference = extendLedgerApiUserWithCaseId(super.wc(name))

    override def uamc(name: String)(implicit
        env: SpliceTestConsoleEnvironment
    ): AppManagerAppClientReference = extendLedgerApiUserWithCaseId(super.uamc(name))

    // make `aliceAns` etc. use updated usernames
    override def rdpe(name: String)(implicit
        env: SpliceTestConsoleEnvironment
    ): AnsExternalAppClientReference =
      extendLedgerApiUserWithCaseId(super.rdpe(name))

    // make `aliceSplitwell` etc. use updated usernames
    override def rsw(name: String)(implicit
        env: SpliceTestConsoleEnvironment
    ): SplitwellAppClientReference = extendLedgerApiUserWithCaseId(super.rsw(name))(env.actorSystem)

    override def perTestCaseName(name: String)(implicit env: SpliceTestConsoleEnvironment) =
      s"${name}_tc$testCaseId.unverified.$ansAcronym"
    def perTestCaseNameWithoutUnverified(name: String) = s"${name}_tc$testCaseId"

    private def extendLedgerApiUserWithCaseId(
        ref: WalletAppClientReference
    ): WalletAppClientReference = {
      val newLedgerApiUser = perTestCaseNameWithoutUnverified(ref.config.ledgerApiUser)
      new WalletAppClientReference(
        ref.spliceConsoleEnvironment,
        ref.name,
        config = ref.config.copy(ledgerApiUser = newLedgerApiUser),
      )
    }

    private def extendLedgerApiUserWithCaseId(
        ref: AppManagerAppClientReference
    ): AppManagerAppClientReference = {
      val newLedgerApiUser = perTestCaseNameWithoutUnverified(ref.config.ledgerApiUser)
      new AppManagerAppClientReference(
        ref.spliceConsoleEnvironment,
        ref.name,
        config = ref.config.copy(ledgerApiUser = newLedgerApiUser),
      )
    }

    private def extendLedgerApiUserWithCaseId(
        ref: AnsExternalAppClientReference
    ): AnsExternalAppClientReference = {
      val newLedgerApiUser = perTestCaseNameWithoutUnverified(ref.config.ledgerApiUser)
      new AnsExternalAppClientReference(
        ref.spliceConsoleEnvironment,
        ref.name,
        config = ref.config.copy(ledgerApiUser = newLedgerApiUser),
      )
    }

    private def extendLedgerApiUserWithCaseId(
        ref: SplitwellAppClientReference
    )(implicit actorSystem: ActorSystem): SplitwellAppClientReference = {
      val newLedgerApiUser = perTestCaseNameWithoutUnverified(ref.config.ledgerApiUser)
      val newLedgerApiConfig = ref.config.participantClient.ledgerApi
        .copy(authConfig =
          updateUser(ref.config.participantClient.ledgerApi.authConfig, newLedgerApiUser)
        )
      val newRemoteParticipant = ref.config.participantClient.copy(ledgerApi = newLedgerApiConfig)
      new SplitwellAppClientReference(
        ref.spliceConsoleEnvironment,
        ref.name,
        config = ref.config
          .copy(ledgerApiUser = newLedgerApiUser, participantClient = newRemoteParticipant),
      )
    }

    private def updateUser(
        conf: AuthTokenSourceConfig,
        newUser: String,
    ): AuthTokenSourceConfig = {
      conf match {
        case AuthTokenSourceConfig.Static(_, adminToken) => {
          val secret = "test" // used for all of our tests
          val userToken = AuthUtil.LedgerApi.testToken(newUser, secret)
          AuthTokenSourceConfig.Static(userToken, adminToken)
        }
        case AuthTokenSourceConfig.SelfSigned(audience, _, secret, adminToken) => {
          AuthTokenSourceConfig.SelfSigned(audience, newUser, secret, adminToken)
        }
        case _ => conf
      }
    }
  }

  trait TestCommon
      extends BaseTest
      with CommonAppInstanceReferences
      with LedgerApiExtensions
      with AppendedClues {

    protected def testEntryName(implicit env: SpliceTestConsoleEnvironment): String =
      s"mycoolentry.unverified.$ansAcronym"
    protected val testEntryUrl = "https://ans-dir-url.com"
    protected val testEntryDescription = "Sample ANS Entry Description"

    protected def initDso()(implicit env: SpliceTestConsoleEnvironment): Unit = {
      env.fullDsoApps.local.foreach(_.start())
      env.fullDsoApps.local.foreach(_.waitForInitialization())
    }

    protected def initDsoWithSv1Only()(implicit env: SpliceTestConsoleEnvironment): Unit = {
      env.minimalDsoApps.local.foreach(_.start())
      env.minimalDsoApps.local.foreach(_.waitForInitialization())
    }

    def defaultTickDuration(implicit env: SpliceTestConsoleEnvironment): NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofSeconds((sv1Backend.config.onboarding match {
        case Some(foundDso: SvOnboardingConfig.FoundDso) =>
          foundDso.initialTickDuration.asJava
        case Some(_: SvOnboardingConfig.JoinWithKey) |
            Some(_: SvOnboardingConfig.DomainMigration) | None =>
          fail("Failed to retrieve defaultTickDuration from sv1.")
      }).toSeconds)

    def tickDurationWithBuffer(implicit env: SpliceTestConsoleEnvironment) =
      defaultTickDuration.asJava.plus(java.time.Duration.ofSeconds(10))

    def defaultSynchronizerFeesConfig(implicit
        env: SpliceTestConsoleEnvironment
    ): SynchronizerFeesConfig =
      sv1Backend.config.onboarding match {
        case Some(foundDso: SvOnboardingConfig.FoundDso) =>
          foundDso.initialSynchronizerFeesConfig
        case Some(_: SvOnboardingConfig.JoinWithKey) | Some(_: SvOnboardingConfig.DomainMigration) |
            None =>
          fail("Failed to retrieve defaultSynchronizerFeesConfig from sv1.")
      }

    def assertInRange(value: BigDecimal, range: (BigDecimal, BigDecimal))(implicit
        pos: source.Position
    ): Unit =
      value should beWithin(range._1, range._2)

    // Upper bound for fees in any of the above transfers
    // TODO(#10898): Figure out something better for upper bounds of fees
    val smallAmount: BigDecimal = BigDecimal(1.0)
    def beWithin(lower: BigDecimal, upper: BigDecimal): Matcher[BigDecimal] =
      be >= lower and be <= upper
    def beAround(value: BigDecimal): Matcher[BigDecimal] =
      beWithin(value - smallAmount, value + smallAmount)

    /** Asserts two BigDecimals are equal up to `n` decimal digits. */
    def beEqualUpTo(right: BigDecimal, n: Int): Matcher[BigDecimal] =
      Matcher { (left: BigDecimal) =>
        MatchResult(
          left.setScale(n, RoundingMode.HALF_EVEN) == right.setScale(n, RoundingMode.HALF_EVEN),
          s"$left was not equal to $right up to $n digits",
          s"$left was equal to $right up to $n digits",
        )
      }

    /** A function abstracting the common pattern of acting and then waiting for the action to
      * eventually have its expected results.
      */
    def actAndCheck[T, U](
        action: String,
        actionExpr: => T,
    )(check: String, checkFun: T => U): (T, U) = actAndCheck()(action, actionExpr)(check, checkFun)

    /** A function abstracting the common pattern of acting and then waiting for the action to
      * eventually have its expected results.
      */
    def actAndCheck[T, U](
        timeUntilSuccess: FiniteDuration = 20.seconds,
        maxPollInterval: FiniteDuration = 5.seconds,
    )(
        action: String,
        actionExpr: => T,
    )(check: String, checkFun: T => U): (T, U) = {
      {
        val x = clue(s"(act) $action")(actionExpr)
        withClue(s"Check $check for $action") {
          clue(s"(check) $check") {
            eventually(timeUntilSuccess, maxPollInterval) {
              val y = checkFun(x)
              (x, y)
            }
          }
        }
      }
    }

    /** A version of clue that does not emit logger.error message on TestFailedException.
      * Intended to be used when the clue is called inside an outer eventually() loop, in which case
      * we do not want to print errors on failures that will be retried by that external loop.
      */
    def silentClue[T](message: String)(expr: => T): T = {
      logger.debug(s"Running clue: ${message}")
      Try(expr) match {
        case Success(value) =>
          logger.debug(s"Finished clue: ${message}")
          value
        case Failure(ex) =>
          ex match {
            case _: TestFailedException =>
              logger.debug(s"Failed clue: ${message}", ex)
            case _ =>
              logger.error(s"Failed clue: ${message}", ex)
          }
          throw ex
      }
    }

    /** A version of actAndCheck that does not emit logger.error messages on TestFailedException.
      * Intended to be used when this is called inside an outer eventually() loop, in which case
      * we do not want to print errors on failures that will be retried by that external loop.
      */
    def silentActAndCheck[T, U](
        action: String,
        actionExpr: => T,
    )(check: String, checkFun: T => U): (T, U) = {
      {
        val x = silentClue(s"(act) $action")(actionExpr)
        silentClue(s"(check) $check") {
          eventually() {
            val y = checkFun(x)
            (x, y)
          }
        }
      }
    }

    /** Keeps evaluating `testCode` until it succeeds or a timeout occurs.
      */
    def eventuallySucceeds[T](
        timeUntilSuccess: FiniteDuration = 20.seconds,
        maxPollInterval: FiniteDuration = 5.seconds,
    )(testCode: => T): T = {
      eventually(timeUntilSuccess, maxPollInterval) {
        try {
          loggerFactory.suppressErrors(testCode)
        } catch {
          case e: TestFailedException => throw e
          case NonFatal(e) => fail(e)
        }
      }
    }

    /** Changes `name` so it is unlikely to conflict with names used somewhere else.
      * Does nothing for isolated test environments, overloaded for shared environment.
      */
    def perTestCaseName(name: String)(implicit env: SpliceTestConsoleEnvironment) =
      s"${name}.unverified.$ansAcronym"

    private def readMandatoryEnvVar(name: String): String = {
      sys.env.get(name) match {
        case None => fail(s"Environment variable $name must be set")
        case Some(s) if s.isEmpty => fail(s"Environment variable $name must be non-empty")
        case Some(s) => s
      }
    }

    def auth0UtilFromEnvVars(tenant: String): Auth0Util = {
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

      retryAuth0Calls(new Auth0Util(domain, clientId, clientSecret, loggerFactory))
    }

    def retryAuth0Calls[T](f: => T): T = {
      eventually() {
        try {
          f
        } catch {
          case auth0Exception: Auth0Exception => {
            logger.debug("Auth0 exception raised, triggering retry...")
            fail(auth0Exception)
          }
          case ioException: java.io.IOException => {
            logger.debug("IOException raised, triggering retry...")
            fail(ioException)
          }
          case ex: Throwable => throw ex // throw anything else
        }
      }
    }

    /** Overrides the retry policy for ALL grpc commands executed in the given block */
    def withCommandRetryPolicy[T](
        policy: GrpcAdminCommand[?, ?, ?] => GrpcError => Boolean
    )(block: => T)(implicit env: SpliceTestConsoleEnvironment): T = {
      val prevD = env.grpcDomainCommandRunner.retryPolicy
      val prevL = env.grpcLedgerCommandRunner.retryPolicy
      val prevA = env.grpcAdminCommandRunner.retryPolicy
      try {
        env.grpcDomainCommandRunner.setRetryPolicy(policy)
        env.grpcLedgerCommandRunner.setRetryPolicy(policy)
        env.grpcAdminCommandRunner.setRetryPolicy(policy)
        block
      } finally {
        env.grpcDomainCommandRunner.setRetryPolicy(prevD)
        env.grpcLedgerCommandRunner.setRetryPolicy(prevL)
        env.grpcAdminCommandRunner.setRetryPolicy(prevA)
      }
    }

    implicit def javaToScalaContractId[T](cid: ContractId[T]): LfContractId =
      LfContractId.assertFromString(cid.contractId)

    protected def startAllSync(nodes: AppBackendReference*): Unit = {
      nodes.foreach(_.start())
      nodes.foreach(_.waitForInitialization())
    }

    protected def stopAllAsync(
        nodes: AppBackendReference*
    )(implicit ec: ExecutionContext): Future[Unit] = {
      nodes.parTraverse(node => Future { node.stop() }).map(_ => ())
    }

    def registerHttpConnectionPoolsCleanup(implicit
        env: TestEnvironment[EnvironmentImpl]
    ): Unit = {
      implicit val sys = env.actorSystem
      implicit val ec = env.executionContext
      CoordinatedShutdown(sys).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "cleanup") {
        () =>
          Http().shutdownAllConnectionPools().map(_ => Done)
      }
    }
  }

  object BracketSynchronous {

    /** Start a synchronous ad-hoc bracket that puts the cleanup immediately
      * after the creation.  Sort of like try/finally but written backwards.
      *
      * {{{
      *  bracket(doSetup(), doCleanupFromSetup()) {
      *    doOtherThings
      *  }
      * }}}
      */
    @nowarn("cat=unused-params")
    def bracket[T](acquire: Any, release: => Any)(body: => T): T =
      try body
      finally release
  }
}
