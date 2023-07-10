import { ErrorBoundary, ErrorDisplay, Loading, PartyId, useUserState } from 'common-frontend';
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
import { isDarFileMissingError } from '../utils/errors';

const Root: React.FC = () => {
  const { logout } = useUserState();
  const primaryPartyQuery = usePrimaryParty();
  const primaryPartyId = primaryPartyQuery.data;
  const directoryInstall = useDirectoryInstall();
  const { data: providerPartyId } = useProviderParty();

  const ledgerApiClient = useLedgerApiClient();
  const requestDirectoryInstall = useRequestDirectoryInstall();

  useEffect(() => {
    if (
      requestDirectoryInstall.isIdle &&
      directoryInstall.isSuccess &&
      primaryPartyId &&
      providerPartyId &&
      ledgerApiClient
    ) {
      if (directoryInstall.data) {
        console.debug('DirectoryInstall found');
      } else {
        console.debug('DirectoryInstall not found, creating DirectoryInstallRequest');
        requestDirectoryInstall.mutate({ primaryPartyId, providerPartyId, ledgerApiClient });
      }
    }
  }, [
    directoryInstall.isSuccess,
    directoryInstall.data,
    ledgerApiClient,
    primaryPartyId,
    providerPartyId,
    requestDirectoryInstall,
  ]);

  let content;
  if (isDarFileMissingError(directoryInstall.error)) {
    const missingTemplates = directoryInstall.error.warnings.unknownTemplateIds.join(', ');
    content = (
      <ErrorDisplay
        message="A required template was not found on the participant"
        userAction="Make sure the application operator has uploaded all relevant DAR files."
        details={`Missing templates: ${missingTemplates}`}
        retryFn={() => directoryInstall.refetch()}
      />
    );
  } else if (directoryInstall.error !== null) {
    content = (
      <ErrorDisplay
        message="Problem loading directory install contract"
        userAction="Please try again later. If the problem persists, contact the application operator."
      />
    );
  } else if (primaryPartyQuery.error !== null) {
    content = (
      <ErrorDisplay
        message="Problem loading primary party"
        userAction="Please try again later. If the problem persists, contact the application operator."
      />
    );
  } else if (directoryInstall.isLoading || primaryPartyQuery.isLoading) {
    content = <Loading />;
  } else {
    content = <Outlet />;
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
              <PartyId partyId={primaryPartyId} id="logged-in-user" />
            )}

            <Button color="inherit" onClick={logout} id="logout-button">
              Log Out
            </Button>
          </Toolbar>
        </Box>
        {/* no need to show the app if it won't be usable. Creating DirectoryInstall should be fast. */}
        {directoryInstall.data ? content : <Loading />}
      </Box>
    </ErrorBoundary>
  );
};

export default Root;
