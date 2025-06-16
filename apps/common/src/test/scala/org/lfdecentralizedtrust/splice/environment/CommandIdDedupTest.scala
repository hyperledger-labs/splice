package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.BaseTest
import org.scalatest.wordspec.AnyWordSpecLike
import SpliceLedgerConnection.CommandId
import com.digitalasset.canton.topology.PartyId

import java.security.MessageDigest

class CommandIdDedupTest extends BaseTest with AnyWordSpecLike {

  "CommandId dedup" should {

    def partyFor(name: String) = PartyId.tryFromProtoPrimitive(name + "::dummy")
    val alice = partyFor("alice")
    val bob = partyFor("bob")

    "prevent collisions with single-items" in {
      val commandId1 = CommandId("method", Seq(alice, bob), "")
      val commandId2 = CommandId("method", Seq(alice), bob.toProtoPrimitive)
      commandId1.commandIdForSubmission shouldNot equal(commandId2.commandIdForSubmission)
    }

    "prevent collisions with multiple-items" in {
      val commandId1 = CommandId("method", Seq(alice, bob), "")
      val commandId2 = CommandId("method", Seq(), Seq(alice.toProtoPrimitive, bob.toProtoPrimitive))
      commandId1.commandIdForSubmission shouldNot equal(commandId2.commandIdForSubmission)
    }

    "keep old behavior for single items to guarantee backwards compatibility during upgrades" in {
      val commandId1 = CommandId("method", Seq(alice, bob), "disc")
      val commandId2 = oldDedup("method", Seq(alice, bob), "disc")
      commandId1.commandIdForSubmission should equal(commandId2)

      val commandId3 = CommandId("method", Seq(alice, bob), "")
      val commandId4 = oldDedup("method", Seq(alice, bob), "")
      commandId3.commandIdForSubmission should equal(commandId4)
    }

    def oldDedup(methodName: String, parties: Seq[PartyId], discriminator: String) = {
      val str = parties
        .map(_.toProtoPrimitive)
        .prepended(
          parties.length.toString
        )
        .appended(discriminator)
        .mkString("/")
      val hashFun = MessageDigest.getInstance("SHA-256")
      val hash = hashFun.digest(str.getBytes("UTF-8")).map("%02x".format(_)).mkString
      s"${methodName}_$hash"
    }

  }

}
