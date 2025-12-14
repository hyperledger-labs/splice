// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.store

import org.lfdecentralizedtrust.splice.environment.CommandPriority
import org.lfdecentralizedtrust.splice.util.ContractWithState
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  validatorlicense as validatorLicenseCodegen,
}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

/** Declares functions available when a WalletStore also queries ValidatorLicenses */
trait ValidatorLicenseStore extends WalletStore {

  def lookupValidatorLicenseWithOffset()(implicit tc: TraceContext): Future[
    QueryResult[Option[ContractWithState[
      validatorLicenseCodegen.ValidatorLicense.ContractId,
      validatorLicenseCodegen.ValidatorLicense,
    ]]]
  ]
}

/** Declares a utility function required whenever lookupValidatorLicenseWithOffset is used */
trait FetchCommandPriority {
   def getCommandPriority () (implicit
      tc: TraceContext
   ): Future [CommandPriority];
}
