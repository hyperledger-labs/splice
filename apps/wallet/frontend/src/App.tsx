import { useAuth0 } from '@auth0/auth0-react';
import { ErrorBoundary, DirectoryEntry } from 'common-frontend';
import {
  UserStatusRequest,
  WalletContext,
} from 'common-protobuf/com/daml/network/wallet/v0/wallet_service_pb';
import { Dispatch, Reducer, useCallback, useEffect, useReducer } from 'react';

import {
  Alert,
  AppBar,
  Box,
  Button,
  Container,
  CssBaseline,
  Toolbar,
  Typography,
} from '@mui/material';

import './App.css';
import { useWalletClient } from './contexts/WalletServiceContext';
import Home from './views/Home';
import Login from './views/Login';
import Onboarding from './views/Onboarding';

type State =
  | {
      type: 'logged_out';
      damlUserId?: undefined;
    }
  | {
      type: 'awaiting_status';
      damlUserId: string;
    }
  | {
      type: 'onboarded';
      damlUserId: string;
      onboarded: true;
      damlPartyId: string;
    }
  | {
      type: 'not_onboarded';
      damlUserId: string;
      onboarded: false;
    };

const initialState: State = {
  type: 'logged_out',
};

type Action =
  | {
      type: 'manual_login';
      damlUserId: string;
    }
  | {
      type: 'logout';
    }
  | {
      type: 'auth0_login';
      damlUserId: string;
    }
  | {
      type: 'user_status_response';
      damlUserId: string;
      onboarded: boolean;
      damlPartyId: string;
    };

const reducer: Reducer<State, Action> = (state: State, action: Action) => {
  switch (action.type) {
    case 'manual_login':
      return { type: 'awaiting_status', damlUserId: action.damlUserId };
    case 'auth0_login':
      return { type: 'awaiting_status', damlUserId: action.damlUserId };
    case 'logout':
      return { type: 'logged_out' };
    case 'user_status_response':
      if (state.type === 'logged_out' || action.damlUserId !== state.damlUserId) {
        // Response does not belong to the current user
        return state;
      } else {
        if (action.onboarded) {
          return {
            type: 'onboarded',
            onboarded: true,
            damlUserId: action.damlUserId,
            damlPartyId: action.damlPartyId,
          };
        } else {
          return { type: 'not_onboarded', onboarded: false, damlUserId: action.damlUserId };
        }
      }
  }
};

const App: React.FC = () => {
  // TODO(i701) -- create a React context to manage auth state
  const [state, dispatch] = useReducer(reducer, initialState);
  const { user: auth0User, logout: auth0Logout } = useAuth0();

  const walletClient = useWalletClient();

  useEffect(() => {
    if (auth0User?.sub) {
      dispatch({ type: 'auth0_login', damlUserId: auth0User.sub });
    }
  }, [auth0User]);

  const getUserStatus = useCallback(
    async (userId: string | undefined) => {
      if (userId === undefined) return;

      const walletRequestCtx = new WalletContext().setUserName(userId);
      const response = await walletClient.userStatus(
        new UserStatusRequest().setWalletCtx(walletRequestCtx),
        undefined
      );
      dispatch({
        type: 'user_status_response',
        damlUserId: userId,
        onboarded: response.getUserOnboarded(),
        damlPartyId: response.getPartyId(),
      });
    },
    [walletClient, dispatch]
  );

  // Fetch or refresh the onboarding status
  useEffect(() => {
    // Immediately when the user changes
    if (state.type === 'awaiting_status') {
      getUserStatus(state.damlUserId).catch(console.error);
    }
    // Periodically when the user is not onboarded
    if (state.type === 'not_onboarded' || state.type === 'awaiting_status') {
      const timer = setInterval(() => getUserStatus(state.damlUserId).catch(console.error), 5000);
      return () => clearInterval(timer);
    }
  }, [state.type, state.damlUserId, getUserStatus]);

  const Content = (props: { state: State; dispatch: Dispatch<Action> }) => {
    const { state, dispatch } = props;
    switch (state.type) {
      case 'logged_out':
        return <Login onLogin={userId => dispatch({ type: 'manual_login', damlUserId: userId })} />;
      case 'awaiting_status':
        return (
          <>
            <Alert severity="warning">Checking wallet installation...</Alert>
            <Home userId={state.damlUserId} />
          </>
        );
      case 'not_onboarded':
        return <Onboarding userId={state.damlUserId} />;
      case 'onboarded':
        return <Home userId={state.damlUserId} />;
    }
  };

  return (
    <ErrorBoundary>
      <Box height="100%" sx={{ display: 'flex', flexDirection: 'column' }}>
        <CssBaseline />
        <AppBar position="static">
          <Toolbar>
            <Typography variant="h6" sx={{ flexGrow: 1 }} id="app-title">
              CC Wallet
              {state.type === 'onboarded' && (
                <div id="logged-in-user">
                  <DirectoryEntry partyId={state.damlPartyId} />
                </div>
              )}
            </Typography>
            {state.damlUserId && (
              <Button
                color="inherit"
                onClick={() => {
                  dispatch({ type: 'logout' });
                  auth0Logout();
                }}
              >
                Log Out
              </Button>
            )}
          </Toolbar>
        </AppBar>
        <Container style={{ height: '100%', flex: '1' }}>
          <Content state={state} dispatch={dispatch} />
        </Container>
      </Box>
    </ErrorBoundary>
  );
};

export default App;
