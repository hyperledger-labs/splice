import { ErrorDisplay, Loading } from 'common-frontend';
import React, { useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';

import { Stack, Typography } from '@mui/material';

import { useCheckAppAuthorized } from '../hooks';

const Authorize: React.FC = () => {
  const [searchParams] = useSearchParams();

  const redirectUri = searchParams.get('redirect_uri');
  const state = searchParams.get('state');
  const provider = searchParams.get('client_id');

  const authorizeMutation = useCheckAppAuthorized();

  useEffect(() => {
    const checkAuthorization = async () => {
      const response = await authorizeMutation.mutateAsync({
        provider: provider!,
        redirectUri: redirectUri!,
        state: state!,
      });
      window.location.assign(response);
    };
    checkAuthorization();
  }, [authorizeMutation, redirectUri, state, provider]);

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
        Checking for authorization of {provider}
      </Typography>
      {authorizeMutation.isLoading && <Loading />}
      {authorizeMutation.isError && (
        <ErrorDisplay message={`App ${provider} is not authorized: ${authorizeMutation.error}`} />
      )}
    </Stack>
  );
};

export default Authorize;
