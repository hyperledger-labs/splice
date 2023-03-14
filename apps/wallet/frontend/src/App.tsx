import {
  DirectoryEntry,
  ErrorBoundary,
  FeaturedAppRight,
  Login,
  useInterval,
  useScanClient,
  useUserState,
} from 'common-frontend';
import { OnboardedStatus } from 'common-frontend/lib/contexts/UserContext';
import { Decimal } from 'decimal.js';
import { useCallback, useEffect, useState } from 'react';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
} from 'react-router-dom';

import {
  Alert,
  AppBar,
  Box,
  Button,
  CircularProgress,
  Container,
  CssBaseline,
  Toolbar,
  Typography,
} from '@mui/material';

import './App.css';
import SelfFeatureButton from './components/SelfFeatureButton';
import { useWalletClient } from './contexts/WalletServiceContext';
import { config } from './utils/config';
import AppPaymentRequests from './views/AppPaymentRequests';
import Coins from './views/Coins';
import ConfirmPayment from './views/ConfirmPayment';
import ConfirmSubscription from './views/ConfirmSubscription';
import Home from './views/Home';
import Onboarding from './views/Onboarding';
import Subscriptions from './views/Subscriptions';
import Transactions from './views/Transactions';
import TransferOffers from './views/TransferOffers';

const App: React.FC = () => {
  const { isAuthenticated, primaryPartyId, logout } = useUserState();

  return (
    <ErrorBoundary>
      <Box height="100%" sx={{ display: 'flex', flexDirection: 'column' }}>
        <CssBaseline />
        <AppBar position="static">
          <Toolbar>
            <Typography variant="h6" sx={{ flexGrow: 1 }} id="app-title">
              CC Wallet
              {primaryPartyId && (
                <div>
                  <div id="logged-in-user">
                    <DirectoryEntry partyId={primaryPartyId} />
                  </div>
                  <FeaturedAppRight partyId={primaryPartyId} />
                  <SelfFeatureButton />
                </div>
              )}
            </Typography>
            {isAuthenticated && (
              <Button color="inherit" onClick={logout} id="logout-button">
                Log Out
              </Button>
            )}
          </Toolbar>
        </AppBar>
        <Container style={{ height: '100%', flex: '1' }}>
          {isAuthenticated ? (
            <Content />
          ) : (
            <Login
              title={'Wallet Log In'}
              authConfig={config.auth}
              testAuthConfig={config.testAuth}
            />
          )}
        </Container>
      </Box>
    </ErrorBoundary>
  );
};

const Content = () => {
  const { updateStatus, onboardedStatus, isAuthenticated, userId } = useUserState();
  const walletClient = useWalletClient();

  // show an error page if we suspect an auth misconfiguration
  const [authError, setAuthError] = useState(false);

  const [coinPrice, setCoinPrice] = useState<Decimal | undefined>();
  const scanClient = useScanClient();

  const fetchCoinPrice = useCallback(async () => {
    const coinPriceBD = await scanClient.getCoinPrice();
    const coinPrice = new Decimal(coinPriceBD.toString());
    // avoid unnecessary re-renders everytime the coin price is fetched but does not change.
    setCoinPrice(prevCoinPrice => (prevCoinPrice?.equals(coinPrice) ? prevCoinPrice : coinPrice));
  }, [scanClient]);

  useInterval(fetchCoinPrice, 2000);

  const routes = createBrowserRouter(
    createRoutesFromElements(
      <Route path="/" element={<Home />}>
        <Route index element={<Coins />} />
        <Route path="coins" element={<Coins />} />
        <Route path="transfer-offers" element={<TransferOffers />} />
        <Route path="subscriptions" element={<Subscriptions />}></Route>
        <Route
          path="app-payment-requests"
          element={<AppPaymentRequests coinPrice={coinPrice} />}
        ></Route>
        <Route path="confirm-payment/:cid/" element={<ConfirmPayment coinPrice={coinPrice} />} />
        <Route path="confirm-subscription/:cid/" element={<ConfirmSubscription />} />
        <Route path="transactions" element={<Transactions />} />
      </Route>
    )
  );

  const getUserStatus = useCallback(
    async (userId: string | undefined) => {
      console.debug(`Checking status for user ${userId}`);
      if (userId === undefined) return;

      const status = await walletClient.userStatus();
      updateStatus(status);
      setAuthError(false);
    },
    [walletClient, updateStatus]
  );

  // Fetch or refresh the onboarding status
  useEffect(() => {
    const tryGetUserStatus = () => {
      getUserStatus(userId).catch(e => {
        if (e.code === 16) {
          setAuthError(true);
        }
        console.error(e);
      });
    };
    if (isAuthenticated) {
      tryGetUserStatus();

      // Periodically when the user is not onboarded
      if (onboardedStatus !== OnboardedStatus.Onboarded) {
        const timer = setInterval(tryGetUserStatus, 5000);
        return () => clearInterval(timer);
      }
    }
  }, [onboardedStatus, isAuthenticated, userId, getUserStatus]);

  const boxSx = {
    width: '100%',
    height: '100%',
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
  };
  if (authError) {
    return (
      <Box sx={boxSx}>
        <Alert id="auth-error" sx={{ display: 'flex' }} severity="error">
          Authorization problem detected. Is the app backend configured to support the login method
          you chose?
        </Alert>
      </Box>
    );
  } else if (onboardedStatus === OnboardedStatus.Loading) {
    return (
      <Box sx={boxSx}>
        <CircularProgress sx={{ display: 'flex' }} />
      </Box>
    );
  }

  return onboardedStatus === OnboardedStatus.Onboarded ? (
    <RouterProvider router={routes} />
  ) : (
    <Onboarding onOnboard={() => getUserStatus(userId)} />
  );
};

export default App;
