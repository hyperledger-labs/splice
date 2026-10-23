package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_25
class ProvisionalFeaturedAppLockConversionIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with GovernanceLockTest {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)

  private val lockAmount = BigDecimal(10000)

  // Conversion archives the lock and creates a new one, so the kind is looked up by provider
  // rather than by contract id.
  private def lockKindFor(
      provider: PartyId
  )(implicit env: SpliceTestConsoleEnvironment): governancelock.GovernanceLockKind =
    GovernanceLockUtil.getGovernanceLockKind(
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(governancelock.GovernanceLock.COMPANION)(
          dsoParty,
          c =>
            GovernanceLockUtil.getGovernanceLockKind(c.data) match {
              case kind: governancelock.governancelockkind.GLK_ProvisionalFeaturedApp =>
                kind.provider == provider.toProtoPrimitive
              case kind: governancelock.governancelockkind.GLK_FeaturedApp =>
                kind.provider == provider.toProtoPrimitive
              case _ => false
            },
        )
        .loneElement
        .data
    )

  private def provisionalFor(provider: PartyId): governancelock.GovernanceLockKind =
    new governancelock.governancelockkind.GLK_ProvisionalFeaturedApp(provider.toProtoPrimitive)

  private def featuredFor(provider: PartyId): governancelock.GovernanceLockKind =
    new governancelock.governancelockkind.GLK_FeaturedApp(provider.toProtoPrimitive)

  "convert a provisional featured app lock once its provider has a FeaturedAppRight" in {
    implicit env =>
      val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bob = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val charlie = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)

      aliceWalletClient.tap(lockAmount)
      bobWalletClient.tap(lockAmount)

      actAndCheck(
        "alice locks for charlie and bob locks for alice", {
          createGovernanceLockTSv1(
            aliceValidatorBackend.participantClientWithAdminToken,
            RichPartyId.local(alice),
            lockKind = ProvisionalFeaturedAppLock,
            lockSubject = charlie.toProtoPrimitive,
            amount = lockAmount,
          )
          createGovernanceLockTSv1(
            bobValidatorBackend.participantClientWithAdminToken,
            RichPartyId.local(bob),
            lockKind = ProvisionalFeaturedAppLock,
            lockSubject = alice.toProtoPrimitive,
            amount = lockAmount,
          )
        },
      )(
        "both locks start out provisional",
        _ => {
          lockKindFor(charlie) shouldBe provisionalFor(charlie)
          lockKindFor(alice) shouldBe provisionalFor(alice)
        },
      )

      actAndCheck(
        "charlie grants himself a FeaturedAppRight",
        eventuallySucceeds() {
          charlieWalletClient.selfGrantFeaturedAppRight()
        },
      )(
        "charlie's lock is converted and alice's is left alone",
        _ => {
          lockKindFor(charlie) shouldBe featuredFor(charlie)
          lockKindFor(alice) shouldBe provisionalFor(alice)
        },
      )
  }
}
