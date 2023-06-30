import { ErrorBoundary, Loading, PartyId, useUserState } from 'common-frontend';
import { useEffect } from 'react';
import { Outlet } from 'react-router-dom';

import { Box, Button, Toolbar, Typography } from '@mui/material';

import { useLedgerApiClient } from '../contexts/LedgerApiContext';
import {
  useDirectoryInstall,
  usePrimaryParty,
  useProviderParty,
  useRequestDirectoryInstall,
} from '../hooks';

const Root: React.FC = () => {
  const { logout } = useUserState();
  const primaryPartyQuery = usePrimaryParty();
  const primaryPartyId = primaryPartyQuery.data;
  const { data: directoryInstallContract, isLoading: isDirectoryInstallContractLoading } =
    useDirectoryInstall();
  const { data: providerPartyId } = useProviderParty();

  const ledgerApiClient = useLedgerApiClient();
  const requestDirectoryInstall = useRequestDirectoryInstall();

  useEffect(() => {
    if (
      requestDirectoryInstall.isIdle &&
      !isDirectoryInstallContractLoading &&
      primaryPartyId &&
      providerPartyId &&
      ledgerApiClient
    ) {
      if (directoryInstallContract) {
        console.debug('DirectoryInstall found');
      } else {
        console.debug('DirectoryInstall not found, creating DirectoryInstallRequest');
        requestDirectoryInstall.mutate({ primaryPartyId, providerPartyId, ledgerApiClient });
      }
    }
  }, [
    directoryInstallContract,
    isDirectoryInstallContractLoading,
    ledgerApiClient,
    primaryPartyId,
    providerPartyId,
    requestDirectoryInstall,
  ]);

  if (primaryPartyQuery.isLoading) {
    return <Loading />;
  }

  return (
    <ErrorBoundary>
      <Box height="100%" sx={{ display: 'flex', flexDirection: 'column' }}>
        <Box bgcolor="colors.neutral.20">
          <Toolbar>
            <Typography
              variant="h4"
              textTransform="uppercase"
              fontFamily={theme => theme.fonts.monospace.fontFamily}
              fontWeight={theme => theme.fonts.monospace.fontWeight}
              sx={{ flexGrow: 1 }}
            >
              Canton Name Service
            </Typography>
            {primaryPartyId && (
              // Using a DirectoryEntry here seems a bit circular
              <div id="logged-in-user">
                <PartyId partyId={primaryPartyId} />
              </div>
            )}

            <Button color="inherit" onClick={logout} id="logout-button">
              Log Out
            </Button>
          </Toolbar>
        </Box>
        {/* no need to show the app if it won't be usable. Creating DirectoryInstall should be fast. */}
        {directoryInstallContract ? <Outlet /> : <Loading />}
      </Box>
    </ErrorBoundary>
  );
};

export default Root;
