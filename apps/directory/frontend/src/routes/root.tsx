import { ErrorBoundary, ErrorDisplay, Loading, PartyId, useUserState } from 'common-frontend';
import { useEffect } from 'react';
import { Outlet } from 'react-router-dom';

import { Box, Button, Toolbar, Typography } from '@mui/material';

import { useDirectoryInstall, useProviderParty, useRequestDirectoryInstall } from '../hooks';
import { usePrimaryParty } from '../hooks/queries/usePrimaryParty';
import { isDarFileMissingError } from '../utils/errors';

const Root: React.FC = () => {
  const { logout } = useUserState();
  const primaryPartyId = usePrimaryParty();
  const directoryInstall = useDirectoryInstall();
  const { data: providerPartyId } = useProviderParty();

  const requestDirectoryInstall = useRequestDirectoryInstall();

  useEffect(() => {
    if (
      requestDirectoryInstall.isIdle &&
      directoryInstall.isSuccess &&
      primaryPartyId &&
      providerPartyId
    ) {
      if (directoryInstall.data) {
        console.debug('DirectoryInstall found');
      } else {
        console.debug('DirectoryInstall not found, creating DirectoryInstallRequest');
        requestDirectoryInstall.mutate({});
      }
    }
  }, [
    directoryInstall.isSuccess,
    directoryInstall.data,
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
  } else if (directoryInstall.isLoading || !directoryInstall.data || !primaryPartyId) {
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
        {content}
      </Box>
    </ErrorBoundary>
  );
};

export default Root;
