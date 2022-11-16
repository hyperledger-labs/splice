package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.directory as codegen
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.Duration
import scala.jdk.CollectionConverters.*

class DirectoryTimeBasedIntegrationTest extends CoinIntegrationTest {

  private val directoryDarPath =
    "apps/directory/daml/.daml/dist/directory-service-0.1.0.dar"
  private val testEntryName = "mycoolentry"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(directoryDarPath)
        bobValidator.remoteParticipant.dars.upload(directoryDarPath)
      })

  "Directory service" should {
    "archive expired directory entries also when running on simtime" in { implicit env =>
      clue("Creating a directory entry that expires immediately") {
        directory.listEntries() shouldBe empty
        val dirParty = directory.getProviderPartyId()
        val now = directory.remoteParticipant.ledger_api.time.get()
        directory.remoteParticipant.ledger_api.commands.submitJava(
          actAs = Seq(dirParty),
          commands = new codegen.DirectoryEntry(
            dirParty.toProtoPrimitive,
            dirParty.toProtoPrimitive,
            testEntryName,
            now.plus(Duration.ofDays(90)).toInstant,
          ).create.commands.asScala.toSeq,
          optTimeout = None,
        )
        eventually()(
          directory.listEntries() should not be empty
        )
      }
      clue("Waiting for the backend to expire the entry...") {
        advanceTime(Duration.ofDays(90).plus(Duration.ofSeconds(10)))
        eventually()(
          directory.listEntries() shouldBe empty
        )
      }
    }
  }
}
