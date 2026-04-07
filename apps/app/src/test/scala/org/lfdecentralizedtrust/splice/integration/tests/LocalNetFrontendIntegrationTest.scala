package org.lfdecentralizedtrust.splice.integration.tests

import io.circe.parser.decode
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.client.RequestBuilding.{Get, Post}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import org.lfdecentralizedtrust.splice.auth.AuthUtil
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.{FrontendLoginUtil, WalletFrontendTestUtil}
import com.digitalasset.canton.http.json.v2.JsStateServiceCodecs.*
import com.daml.ledger.api.v2.state_service.GetConnectedSynchronizersResponse
import com.daml.grpc.AuthCallCredentials
import com.digitalasset.canton.admin.api.client.commands.{GrpcAdminCommand, LedgerApiCommands}
import org.apache.pekko.stream.scaladsl.FileIO
import com.daml.ledger.api.v2.commands.{Command, CreateCommand, ExerciseByKeyCommand}
import com.daml.ledger.api.v2.transaction_filter.TransactionShape
import com.daml.ledger.api.v2.value.{Identifier, Record, RecordField, Value}
import com.digitalasset.canton.LfPartyId
import com.digitalasset.canton.config.NonNegativeDuration

import java.nio.file.Paths
import scala.concurrent.duration.*
import scala.sys.process.*

class LocalNetFrontendIntegrationTest
    extends FrontendIntegrationTestWithIsolatedEnvironment("frontend")
    with FrontendLoginUtil
    with WalletFrontendTestUtil {
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .fromResources(Seq("localnet-topology.conf"), this.getClass.getSimpleName)
      .withManualStart

  // This does nothing as the wallet clients will not actually be connected to the compose setup
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override lazy val resetRequiredTopologyState = false

  val partyHint = "localnet-localparty-1"

  private def withLocalNet(
      additionalArgs: Seq[String]
  )(f: FixtureParam => Any)(implicit env: FixtureParam): Unit =
    try {
      val ret = (Seq("build-tools/splice-localnet-compose.sh", "start") ++ additionalArgs).!
      if (ret != 0) {
        fail("Failed to start docker-compose SV and validator")
      }
      f(env)
    } finally {
      (Seq("build-tools/splice-localnet-compose.sh", "stop", "-D") ++ additionalArgs).!
    }

  private def testValidators(implicit env: FixtureParam): Unit =
    clue("Test validators") {
      List(
        ("app-user", 2000, "app_user"),
        ("app-provider", 3000, "app_provider"),
      ).foreach { case (user, port, partyHintPrefix) =>
        clue(s"Test $user validator") {
          val host = "wallet.localhost"
          val url = s"http://$host:$port"
          withFrontEnd("frontend") { implicit webDriver =>
            eventuallySucceeds()(go to url)
            actAndCheck(timeUntilSuccess = 60.seconds)(
              s"Login as $user",
              login(port, user, host),
            )(
              s"$user is already onboarded",
              _ =>
                seleniumText(find(id("logged-in-user"))) should startWith(
                  s"${partyHintPrefix}_$partyHint"
                ),
            )
            // Wait for some traffic to be bought before proceeding so that we don't hit a "traffic below reserved amount" error
            waitForTrafficPurchase()
            go to url
            actAndCheck(
              s"Login as $user",
              loginOnCurrentPage(port, user, host),
            )(
              s"$user is already onboarded",
              _ =>
                seleniumText(find(id("logged-in-user"))) should startWith(
                  s"${partyHintPrefix}_$partyHint"
                ),
            )
            tapAmulets(345.6)
          }
        }
      }
    }

  private def testSvUi(): Unit =
    clue("Basic test of SV UIs") {
      withFrontEnd("frontend") { implicit webDriver =>
        actAndCheck(
          "Open the Scan UI",
          go to "scan.localhost:4000",
        )(
          "Open rounds should be listed",
          _ => findAll(className("open-mining-round-row")) should have length 2,
        )

        actAndCheck(timeUntilSuccess = 60.seconds)(
          "Login to the wallet as sv",
          login(4000, "sv", "wallet.localhost"),
        )(
          "sv is already onboarded",
          _ => seleniumText(find(id("logged-in-user"))) should startWith("sv.sv.ans"),
        )

        actAndCheck()(
          "Login to the SV app as sv",
          login(4000, "sv", "sv.localhost"),
        )(
          "sv is already onboarded, and the app is working",
          _ => {
            seleniumText(
              find(id("svUser")).value
                .childElement(className("general-dso-value-name"))
            ) should be("ledger-api-user")
          },
        )
      }
    }

  private val token = AuthUtil.testToken(AuthUtil.testAudience, "ledger-api-user", "unsafe")

  private def testTokenStandardApi(implicit env: FixtureParam): Unit =
    clue("Test token standard APIs") {
      val registryInfo = scancl("scanClient").getRegistryInfo()
      registryInfo.adminId should startWith("DSO::")
      val userRegistryInfo =
        vc("userValidatorClient").copy(token = Some(token)).scanProxy.getRegistryInfo()
      val providerRegistryInfo =
        vc("providerValidatorClient").copy(token = Some(token)).scanProxy.getRegistryInfo()
      registryInfo shouldBe userRegistryInfo
      registryInfo shouldBe providerRegistryInfo
    }

  private def testMultiSynchronizerSupport(isMultiSync: Boolean)(implicit env: FixtureParam): Unit =
    clue("Test multi-synchronizer support") {
      List(
        ("app-user", 2000),
        ("app-provider", 3000),
      ).foreach { case (user, port) =>
        clue(s"Test $user validator") {
          implicit val actorSystem: ActorSystem = env.actorSystem
          registerHttpConnectionPoolsCleanup(env)
          val host = "json-ledger-api.localhost"
          val url = s"http://$host:$port"

          val response =
            Http()
              .singleRequest(
                Get(s"$url/v2/state/connected-synchronizers")
                  .withHeaders(Seq(Authorization(OAuth2BearerToken(token))))
              )
              .futureValue
          response.status should be(StatusCodes.OK)
          val payload = response.entity.toStrict(10.seconds).futureValue.data.utf8String
          val message = decode[GetConnectedSynchronizersResponse](payload).getOrElse(
            fail("Failed to decode response from /v2/state/connected-synchronizers")
          )
          val synchronizers = message.connectedSynchronizers.map(_.synchronizerAlias)
          if (isMultiSync)
            synchronizers should contain allOf ("global", "app-synchronizer")
          else
            synchronizers should contain only "global"
        }
      }
    }

  "docker-compose based localnet works for single synchronizer" in { implicit env =>
    withLocalNet(Seq.empty) { implicit env =>
      testValidators
      testSvUi()
      testTokenStandardApi
      testMultiSynchronizerSupport(isMultiSync = false)
    }
  }

  "docker-compose based localnet works for multiple synchronizers" in { implicit env =>
    withLocalNet(Seq("-M")) { implicit env =>
      testMultiSynchronizerSupport(isMultiSync = true)
    }
  }

  "localnet supports configurable protocol versions" in { implicit env =>
    withLocalNet(Seq("-P", "35")) { implicit env =>
      implicit val actorSystem: ActorSystem = env.actorSystem
      registerHttpConnectionPoolsCleanup(env)
      val host = "json-ledger-api.localhost"
      val port = 3000 // JSON API of app provider
      val url = s"http://$host:$port"
      val filePath = Paths.get("apps/app/src/test/resources/nuck-example-main-0.0.1.dar")
      val entity = HttpEntity(
        ContentTypes.`application/octet-stream`,
        FileIO.fromPath(filePath)
      )
      val response =
        Http()
          .singleRequest(
            Post(s"$url/v2/packages")
              .withHeaders(Seq(Authorization(OAuth2BearerToken(token))))
              .withEntity(entity)
          )
          .futureValue
      response.status should be(StatusCodes.OK)

      val grpcHost = "grpc-ledger-api.localhost"
      val channel = io.grpc.ManagedChannelBuilder.forAddress(grpcHost, port).usePlaintext().build()

      def runGrpcCommand[Req, Res, Result](command: GrpcAdminCommand[Req, Res, Result]): Result = {
        val service = command.createServiceInternal(channel)
          .withCallCredentials(new AuthCallCredentials(token))
        val request = command.createRequestInternal().value
        val response = command.submitRequestInternal(service, request).futureValue
        command.handleResponseInternal(response).value
      }

      runGrpcCommand(LedgerApiCommands.PackageManagementService.UploadDarFile(
        darPath = filePath.toAbsolutePath.toString,
        synchronizerId = None,
      ))

      val alice = runGrpcCommand(LedgerApiCommands.PartyManagementService.AllocateParty(
        partyIdHint = "alice",
        annotations = Map.empty,
        identityProviderId = "",
        synchronizerId = None,
        userId = "ledger-api-user",
      )).party

      val bob = runGrpcCommand(LedgerApiCommands.PartyManagementService.AllocateParty(
        partyIdHint = "bob",
        annotations = Map.empty,
        identityProviderId = "",
        synchronizerId = None,
        userId = "ledger-api-user",
      )).party
      println(s"bob party ID: $bob")

      val createCommand = Command.of(
        Command.Command.Create(
          CreateCommand(
            templateId = Some(Identifier(
              packageId = "22ce2c5b30d288f6aa48094d9775618851fec952c86cf1b7904a2eaaac27190d",
              moduleName = "Main",
              entityName = "Asset",
            )),
            createArguments = Some(Record(
              recordId = Some(Identifier(
                packageId = "22ce2c5b30d288f6aa48094d9775618851fec952c86cf1b7904a2eaaac27190d",
                moduleName = "Main",
                entityName = "Asset",
              )),
              fields = Seq(
                RecordField("issuer", Some(Value(Value.Sum.Party(alice)))),
                RecordField("owner", Some(Value(Value.Sum.Party(alice)))),
                RecordField("name", Some(Value(Value.Sum.Text("Sofa")))),
              ),
            )),
          )
        )
      )
      runGrpcCommand(LedgerApiCommands.CommandService.SubmitAndWaitTransaction(
        actAs = Seq(LfPartyId.fromString(alice).value),
        readAs = Seq.empty,
        commands = Seq(createCommand),
        workflowId = "",
        commandId = "",
        deduplicationPeriod = None,
        submissionId = "",
        minLedgerTimeAbs = None,
        disclosedContracts = Seq.empty,
        synchronizerId = None,
        userId = "ledger-api-user",
        packageIdSelectionPreference = Seq.empty,
        transactionShape = TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS,
        includeCreatedEventBlob = false,
        tapsMaxPasses = None,
        optTimeout = Some(NonNegativeDuration.tryFromDuration(30.seconds)),
      ))

      //   bobTVId <- submit alice do
      //    exerciseByKeyCmd @Asset (alice, "TV") Give with newOwner = bob
      val newOwnerCmd = Command.of(
        Command.Command.ExerciseByKey(
          ExerciseByKeyCommand(
            templateId = Some(Identifier(
              packageId = "22ce2c5b30d288f6aa48094d9775618851fec952c86cf1b7904a2eaaac27190d",
              moduleName = "Main",
              entityName = "Asset",
            )),
            contractKey = Some(Value(Value.Sum.List(com.daml.ledger.api.v2.value.List(Seq(
              Value(Value.Sum.Party(alice)),
              Value(Value.Sum.Text("Sofa")),
            ))))),
            choice = "Give",
            choiceArgument = Some(Value(Value.Sum.Record(Record(
              recordId = Some(Identifier(
                packageId = "22ce2c5b30d288f6aa48094d9775618851fec952c86cf1b7904a2eaaac27190d",
                moduleName = "Main",
                entityName = "Give",
              )),
              fields = Seq(
                RecordField("newOwner", Some(Value(Value.Sum.Party(bob)))),
              ),
            )))),
          )
        ))
      runGrpcCommand(LedgerApiCommands.CommandService.SubmitAndWaitTransaction(
        actAs = Seq(LfPartyId.fromString(alice).value),
        readAs = Seq.empty,
        commands = Seq(newOwnerCmd),
        workflowId = "",
        commandId = "",
        deduplicationPeriod = None,
        submissionId = "",
        minLedgerTimeAbs = None,
        disclosedContracts = Seq.empty,
        synchronizerId = None,
        userId = "ledger-api-user",
        packageIdSelectionPreference = Seq.empty,
        transactionShape = TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS,
        includeCreatedEventBlob = false,
        tapsMaxPasses = None,
        optTimeout = Some(NonNegativeDuration.tryFromDuration(30.seconds)),
      ))

    }
  }
}
