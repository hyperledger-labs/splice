import { Auth0Manager } from './auth0';

export function logInUser(auth0: Auth0Manager, email: string, password: string): string {
  const userExists = auth0.userExists(email);
  if (!userExists) {
    auth0.createUser(email, password);
  }

  return auth0.authorizationCodeGrant(email, password);
}
