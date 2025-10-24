// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.{
  LicenseKind,
  ValidatorLicense,
}
import scala.jdk.OptionConverters.*

class SvValidatorLicenseIntegrationTest extends SvIntegrationTestBase {

  "grant validator license via admin API" in { implicit env =>
    initDso()

    val dsoParty = sv1Backend.getDsoInfo().dsoParty
    val sv1Party = sv1Backend.getDsoInfo().svParty
    val validatorParty = allocateRandomSvParty("test-validator", Some(100))

    // Verify no license exists initially
    val initialLicenses = sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(ValidatorLicense.COMPANION)(
        dsoParty,
        c => c.data.validator == validatorParty.toProtoPrimitive,
      )
    initialLicenses should have length 0

    actAndCheck(
      "SV grants validator license to a new party",
      sv1Backend.grantValidatorLicense(validatorParty),
    )(
      "a ValidatorLicense contract is created",
      _ => {
        val licenses = sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(ValidatorLicense.COMPANION)(
            dsoParty,
            c => c.data.validator == validatorParty.toProtoPrimitive,
          )

        licenses should have length 1
        val license = licenses.head
        license.data.validator shouldBe validatorParty.toProtoPrimitive
        license.data.sponsor shouldBe sv1Party.toProtoPrimitive
        license.data.dso shouldBe dsoParty.toProtoPrimitive
      },
    )
  }

  "granting license to an onboarded validator does not affect license kind" in { implicit env =>
    initDso()
    aliceValidatorBackend.startSync()

    val dsoParty = sv1Backend.getDsoInfo().dsoParty

    // Use alice's validator who should already be onboarded with an OperatorLicense
    val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

    // Verify alice already has an OperatorLicense
    val initialLicenses = sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(ValidatorLicense.COMPANION)(
        dsoParty,
        c => c.data.validator == aliceValidatorParty.toProtoPrimitive,
      )
    initialLicenses should have length 1
    val initialLicense = initialLicenses.head
    initialLicense.data.validator shouldBe aliceValidatorParty.toProtoPrimitive
    initialLicense.data.kind.toScala shouldBe Some(LicenseKind.OPERATORLICENSE)

    // Grant a license to Alice (creates NonOperatorLicense)
    sv1Backend.grantValidatorLicense(aliceValidatorParty)

    // Wait for the merge trigger to merge the duplicate licenses
    eventually() {
      val licenses = sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(ValidatorLicense.COMPANION)(
          dsoParty,
          c => c.data.validator == aliceValidatorParty.toProtoPrimitive,
        )

      licenses should have length 1
      val mergedLicense = licenses.head
      // The merged license should still have kind OperatorLicense
      mergedLicense.data.kind.toScala shouldBe Some(LicenseKind.OPERATORLICENSE)
    }
  }
}
