// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import $file.`bootstrap-canton`

println("--- Applying Permissioned Initialization (SV1) ---")

val synchronizerId = eventually() {
  val connected = sv1Participant.domains.list_connected()
  if (connected.isEmpty) sys.error("SV1 not connected to domain yet")
  connected.head.domainId
}

println(s"Granting SV1 (${sv1Participant.id}) Submission permission...")
sv1Participant.topology.participant_synchronizer_permissions.propose(
  synchronizerId,
  sv1Participant.id,
  permission = com.digitalasset.canton.topology.transaction.ParticipantPermission.Submission,
  serial = Some(com.digitalasset.canton.config.RequireTypes.PositiveInt.one),
)

println(s"Locking domain $synchronizerId to RestrictedOpen...")
sv1Participant.topology.synchronizer_parameters.propose_update(
  synchronizerId,
  parameters =>
    parameters.update(
      onboardingRestriction =
        com.digitalasset.canton.admin.api.client.data.OnboardingRestriction.RestrictedOpen
    ),
)

eventually() {
  val currentParams = sv1Participant.topology.synchronizer_parameters
    .get_dynamic_synchronizer_parameters(synchronizerId)

  if (
    currentParams.onboardingRestriction != com.digitalasset.canton.admin.api.client.data.OnboardingRestriction.RestrictedOpen
  ) {
    sys.error("Restriction failed to apply")
  }
}

println(s"--- Permissioned Initialization Complete for SV1 ---")
