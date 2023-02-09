import { Contract } from 'common-frontend';

import { DirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

export interface Entry {
  user: string;
  name: string;
}

class DirectoryEntries {
  entries: Contract<DirectoryEntry>[];
  constructor(entries: Contract<DirectoryEntry>[]) {
    this.entries = entries;
  }

  getAllParties(): string[] {
    return this.entries.map(e => e.payload.user);
  }
  getAllEntries(): Entry[] {
    return this.entries.map(e => e.payload);
  }
}

export default DirectoryEntries;
