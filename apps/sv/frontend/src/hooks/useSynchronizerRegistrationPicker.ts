// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useMemo, useRef, useState } from 'react';
import { Contract as OpenAPIContract } from '@canton-network/sv-openapi';
import { Contract } from '@canton-network/splice-common-frontend-utils';
import {
  GovernanceParameters,
  RegisteredSynchronizer,
} from '@daml.js/splice-amulet/lib/Splice/DecentralizedSynchronizer';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { Option } from '../components/form-components/SelectField';
import { validateSynchronizerId } from '../components/forms/formValidators';
import { useSvAdminClient } from '../contexts/SvAdminServiceContext';
import { useListDsoRulesVoteRequests } from './useListVoteRequests';

export interface ResolvedSynchronizerRegistration {
  contractId: string;
  synchronizerId: string;
  operator: string;
  governanceParameters: GovernanceParameters;
}

const pinnedRegistrationCid = (voteRequest: VoteRequest): string | undefined => {
  const action = voteRequest.action;
  if (action.tag !== 'ARC_DsoRules') return undefined;
  const dsoAction = action.value.dsoAction;
  switch (dsoAction.tag) {
    case 'SRARC_ArchiveSynchronizerRegistration':
    case 'SRARC_SetSynchronizerGovernanceParameters':
      return dsoAction.value.registeredSynchronizerCid;
    default:
      return undefined;
  }
};

const toResolvedRegistration = (contract: OpenAPIContract): ResolvedSynchronizerRegistration => {
  const registration = Contract.decodeOpenAPI(contract, RegisteredSynchronizer);
  return {
    contractId: registration.contractId,
    synchronizerId: registration.payload.synchronizerId,
    operator: registration.payload.operator,
    governanceParameters: registration.payload.governanceParameters,
  };
};

interface SynchronizerRegistrationPicker {
  options: Option[];
  find: (contractId: string) => ResolvedSynchronizerRegistration | undefined;
  loadAndValidate: (synchronizerId: string) => Promise<string | undefined>;
  validateSelection: (contractId: string) => string | undefined;
  reset: () => void;
}

// Offboard and set-parameters votes pin a registration by contract id. Uniqueness of the
// synchronizer id is not enforced on-ledger, so the proposer picks among its active
// registrations, as when revoking a featured app right.
export const useSynchronizerRegistrationPicker = (): SynchronizerRegistrationPicker => {
  const { listSynchronizerRegistrations } = useSvAdminClient();
  const voteRequestsQuery = useListDsoRulesVoteRequests();
  const [registrations, setRegistrationsState] = useState<ResolvedSynchronizerRegistration[]>([]);
  const registrationsRef = useRef<ResolvedSynchronizerRegistration[]>([]);
  const setRegistrations = (rs: ResolvedSynchronizerRegistration[]) => {
    registrationsRef.current = rs;
    setRegistrationsState(rs);
  };
  const latestInput = useRef('');
  const loadedInput = useRef('');

  const options = useMemo(
    () => registrations.map(r => ({ key: r.contractId, value: r.contractId })),
    [registrations]
  );

  const find = (contractId: string) =>
    registrationsRef.current.find(r => r.contractId === contractId);

  const loadAndValidate = async (value: string) => {
    const input = value.trim();
    latestInput.current = input;
    if (loadedInput.current !== input) {
      loadedInput.current = '';
      setRegistrations([]);
    }

    if (validateSynchronizerId(input)) return undefined;

    let contracts;
    try {
      contracts = (await listSynchronizerRegistrations(input)).registrations;
    } catch (e) {
      if (latestInput.current !== input) return undefined;
      console.error('Failed to list the synchronizer registrations', e);
      setRegistrations([]);
      return 'Could not look up the registration. Try again.';
    }
    if (latestInput.current !== input) return undefined;

    loadedInput.current = input;
    const loaded = contracts.map(toResolvedRegistration);
    const unchanged =
      loaded.length === registrationsRef.current.length &&
      loaded.every((r, i) => r.contractId === registrationsRef.current[i].contractId);
    if (!unchanged) setRegistrations(loaded);
    return loaded.length === 0 ? 'No registration found for this synchronizer id.' : undefined;
  };

  const validateSelection = (contractId: string) => {
    if (!contractId) return 'Required';
    if (!find(contractId)) return 'Select a registration of this synchronizer id';
    const targeted = (voteRequestsQuery.data ?? []).some(
      vr => pinnedRegistrationCid(vr.payload) === contractId
    );
    if (targeted) {
      return 'Another open proposal already targets this registration. Only one of them can be executed.';
    }
    return undefined;
  };

  const reset = () => {
    latestInput.current = '';
    loadedInput.current = '';
    setRegistrations([]);
  };

  return { options, find, loadAndValidate, validateSelection, reset };
};
