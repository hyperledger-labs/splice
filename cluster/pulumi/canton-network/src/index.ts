import { Auth0Fetch } from './auth0';
import { installCluster } from './installCluster';

installCluster(new Auth0Fetch());
