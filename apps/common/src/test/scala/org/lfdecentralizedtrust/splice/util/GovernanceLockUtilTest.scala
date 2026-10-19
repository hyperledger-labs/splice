package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.LockedAmulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock as gl
import com.digitalasset.canton.BaseTest
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import org.scalatest.wordspec.AnyWordSpec

class GovernanceLockUtilTest extends AnyWordSpec with BaseTest {

  private val provider = "provider::1220aaaa"
  private val featuredApp = new gl.governancelockkind.GLK_FeaturedApp(provider)
  private val provisionalFeaturedApp =
    new gl.governancelockkind.GLK_ProvisionalFeaturedApp(provider)
  private val superValidator = new gl.governancelockkind.GLK_SuperValidatorRightsOwner("sv1")

  private def lock(kind: gl.GovernanceLockKind, vesting: Boolean): gl.GovernanceLock =
    new gl.GovernanceLock(
      "dso::1220dddd",
      "owner::1220bbbb",
      new BigDecimal(10).setScale(10),
      new LockedAmulet.ContractId("cid"),
      if (vesting)
        new gl.governancelockspecification.GLS_VestingLock(
          new gl.VestingLockSpecification(kind, new RelTime(1_000_000), Instant.EPOCH)
        )
      else
        new gl.governancelockspecification.GLS_FullLock(new gl.FullLockSpecification(kind)),
      Optional.empty(),
      Instant.EPOCH,
    )

  "governanceLockTypeAndSubject" should {
    "encode every kind of full lock" in {
      GovernanceLockUtil.governanceLockTypeAndSubject(
        lock(featuredApp, vesting = false)
      ) shouldBe ("f:fa", provider)
      GovernanceLockUtil.governanceLockTypeAndSubject(
        lock(provisionalFeaturedApp, vesting = false)
      ) shouldBe ("f:pfa", provider)
      GovernanceLockUtil.governanceLockTypeAndSubject(
        lock(superValidator, vesting = false)
      ) shouldBe ("f:sv", "sv1")
    }

    "encode every kind of vesting lock" in {
      GovernanceLockUtil.governanceLockTypeAndSubject(
        lock(featuredApp, vesting = true)
      ) shouldBe ("v:fa", provider)
      GovernanceLockUtil.governanceLockTypeAndSubject(
        lock(provisionalFeaturedApp, vesting = true)
      ) shouldBe ("v:pfa", provider)
      GovernanceLockUtil.governanceLockTypeAndSubject(
        lock(superValidator, vesting = true)
      ) shouldBe ("v:sv", "sv1")
    }
  }
}
