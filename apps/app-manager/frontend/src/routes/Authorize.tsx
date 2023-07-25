import { useUserState } from 'common-frontend';
import React, { useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';

import { Button, Card, CardContent, Stack, Typography } from '@mui/material';

import { useAuthorize } from '../hooks';

const Authorize: React.FC = () => {
  const [searchParams] = useSearchParams();

  const { userId } = useUserState();

  const redirectUri = searchParams.get('redirect_uri');
  const state = searchParams.get('state');

  const authorizeMutation = useAuthorize();

  const onClickHandler = useCallback(async () => {
    const response = await authorizeMutation.mutateAsync({
      redirectUri: redirectUri!,
      state: state!,
      userId: userId!,
    });
    window.location.assign(response);
  }, [authorizeMutation, redirectUri, userId, state]);

  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
        Authorize App
      </Typography>
      <Card variant="outlined">
        <CardContent sx={{ paddingX: '64px' }}>
          <Button
            id="authorize-button"
            variant="pill"
            fullWidth
            size="large"
            onClick={onClickHandler}
            disabled={!userId || !redirectUri || !state}
          >
            Authorize App
          </Button>
        </CardContent>
      </Card>
    </Stack>
  );
};

export default Authorize;
