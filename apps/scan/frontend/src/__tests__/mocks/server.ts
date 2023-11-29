import { setupServer, SetupServer } from 'msw/node';

import { Services } from '../setup/setup';
import { buildScanMock } from './handlers/scan-api';

export const buildServer = ({ scan }: Services): SetupServer =>
  setupServer(...buildScanMock(scan.url));
