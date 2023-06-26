package com.daml.network.store

import com.daml.ledger.javaapi.data.CreatedEvent
import org.scalatest
import better.files.File

import scala.util.Using

class AcsStoreDumpTest extends StoreTest {

  private val p1 = userParty(1)
  private val p2 = userParty(2)
  private val someEvents = Seq(
    toCreatedEvent(appRewardCoupon(0, p1)),
    toCreatedEvent(validatorRewardCoupon(1, p2)),
    toCreatedEvent(appRewardCoupon(2, p2)),
  )

  def testCase(events: Seq[CreatedEvent]): scalatest.Assertion = {
    val dumpDir = File.newTemporaryDirectory("test-acs-store-dumps")
    val tempFile = File.newTemporaryFile(prefix = "acs-store-dump-", parent = Some(dumpDir))
    tempFile.deleteOnExit()

    val readEvents =
      try {
        Using(tempFile.newOutputStream)(AcsStoreDump.writeEvents(events, _))
        Using(tempFile.newInputStream)(AcsStoreDump.readFile)
      } finally tempFile.delete()

    readEvents shouldBe scala.util.Success(events)
  }

  "AcsDump" should {
    "write and read an empty list of events" in testCase(Seq())
    "write and read a list with some events" in testCase(someEvents)
  }
}
