import { ErrorBoundary, PartyId, useUserState } from 'common-frontend';
import { Outlet } from 'react-router-dom';

import { Box, Button, Toolbar, Typography } from '@mui/material';

import { usePrimaryParty } from '../contexts/DirectoryContext';

const Root: React.FC = () => {
  const { logout } = useUserState();
  const primaryPartyId = usePrimaryParty();

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
        <Outlet />
      </Box>
    </ErrorBoundary>
  );
};

export default Root;
