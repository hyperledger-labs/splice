// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { Alert, Box, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { Contract } from '@canton-network/splice-common-frontend-utils';
import {
  GovernanceParameters,
  RegisteredSynchronizer,
} from '@daml.js/splice-amulet/lib/Splice/DecentralizedSynchronizer';
import { useSvAdminClient } from '../../../contexts/SvAdminServiceContext';
import { governanceParameterChanges } from '../../forms/governanceParameterFields';
import { CopyableIdentifier, MemberIdentifier } from '../../ui';
import { ConfigValuesChanges } from '../ConfigValuesChanges';
import { DetailItem } from './DetailItem';

const useRegistrationByContractId = (contractId: string) => {
  const svAdminClient = useSvAdminClient();
  return useQuery({
    queryKey: ['synchronizerRegistrationByContractId', contractId],
    queryFn: async () => {
      const response = await svAdminClient.lookupSynchronizerRegistrationByContractId(contractId);
      return response.registration
        ? Contract.decodeOpenAPI(response.registration, RegisteredSynchronizer).payload
        : null;
    },
  });
};

interface PinnedRegistrationProps {
  idPrefix: string;
  registeredSynchronizerCid: string;
  isOpen: boolean;
  registration: RegisteredSynchronizer | null | undefined;
  isLoaded: boolean;
}

const PinnedRegistration = ({
  idPrefix,
  registeredSynchronizerCid,
  isOpen,
  registration,
  isLoaded,
}: PinnedRegistrationProps) => (
  <>
    {isOpen && isLoaded && !registration && (
      <Alert severity="warning" variant="outlined" data-testid={`${idPrefix}-stale-warning`}>
        The registration this vote targets is no longer active: the synchronizer was offboarded, or
        its parameters were changed, which recreates the registration under a new contract id.
        Executing this vote will fail.
      </Alert>
    )}
    {registration && (
      <>
        <DetailItem
          label="Synchronizer ID"
          value={
            <Typography variant="body1" data-testid={`${idPrefix}-synchronizer-id`}>
              {registration.synchronizerId}
            </Typography>
          }
        />
        <DetailItem
          label="Synchronizer Operator"
          value={
            <MemberIdentifier
              partyId={registration.operator}
              isYou={false}
              size="large"
              fullWidth
              data-testid={`${idPrefix}-operator-party-id`}
            />
          }
        />
      </>
    )}
    <DetailItem
      label="Registration Contract ID"
      value={
        <CopyableIdentifier
          value={registeredSynchronizerCid}
          size="large"
          data-testid={`${idPrefix}-contract-id`}
        />
      }
    />
  </>
);

interface ArchiveSynchronizerRegistrationSectionProps {
  registeredSynchronizerCid: string;
  isOpen: boolean;
}

export const ArchiveSynchronizerRegistrationSection: React.FC<
  ArchiveSynchronizerRegistrationSectionProps
> = ({ registeredSynchronizerCid, isOpen }) => {
  const idPrefix = 'proposal-details-archive-synchronizer';
  const registrationQuery = useRegistrationByContractId(registeredSynchronizerCid);
  return (
    <Box id={`${idPrefix}-section`} data-testid={`${idPrefix}-section`}>
      <PinnedRegistration
        idPrefix={idPrefix}
        registeredSynchronizerCid={registeredSynchronizerCid}
        isOpen={isOpen}
        registration={registrationQuery.data}
        isLoaded={registrationQuery.isSuccess}
      />
    </Box>
  );
};

interface SetSynchronizerGovernanceParametersSectionProps {
  registeredSynchronizerCid: string;
  newGovernanceParameters: GovernanceParameters;
  isOpen: boolean;
}

export const SetSynchronizerGovernanceParametersSection: React.FC<
  SetSynchronizerGovernanceParametersSectionProps
> = ({ registeredSynchronizerCid, newGovernanceParameters, isOpen }) => {
  const idPrefix = 'proposal-details-set-synchronizer-parameters';
  const registrationQuery = useRegistrationByContractId(registeredSynchronizerCid);
  return (
    <Box id={`${idPrefix}-section`} data-testid={`${idPrefix}-section`}>
      <PinnedRegistration
        idPrefix={idPrefix}
        registeredSynchronizerCid={registeredSynchronizerCid}
        isOpen={isOpen}
        registration={registrationQuery.data}
        isLoaded={registrationQuery.isSuccess}
      />
      <DetailItem
        label="Proposed Changes"
        value={
          <ConfigValuesChanges
            changes={governanceParameterChanges(
              registrationQuery.data?.governanceParameters,
              newGovernanceParameters
            )}
          />
        }
      />
    </Box>
  );
};
