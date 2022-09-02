import { DirectoryEntry } from "@daml.js/directory/lib/CN/Directory";
import { Contract } from "./Contract";

export interface Entry {
    user: string,
    name: string,
}

class DirectoryEntries {
    entries: Contract<DirectoryEntry>[];
    constructor (entries: Contract<DirectoryEntry>[]) {
        this.entries = entries;
    }

    getAllParties(): string[] {
        return this.entries.map(e => e.payload.user);
    }
    getAllEntries(): Entry[] {
        return this.entries.map(e => e.payload);
    }
    resolveParty(p: string): string {
        return this.entries.find(e => e.payload.user === p)?.payload.name ?? p;
    }
}

export default DirectoryEntries;
