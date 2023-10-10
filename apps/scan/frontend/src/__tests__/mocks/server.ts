import { setupServer, SetupServer } from 'msw/node';

import { Services } from '../setup/setup';
import { buildDirectoryMock } from './handlers/directory-api';
import { buildScanMock } from './handlers/scan-api';

export const buildServer = ({ scan, directory }: Services): SetupServer =>
  setupServer(...buildScanMock(scan.url), ...buildDirectoryMock(directory.url));
