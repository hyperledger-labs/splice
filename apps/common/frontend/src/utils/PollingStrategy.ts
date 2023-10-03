const defaultInterval = 500; // in ms

export class PollingStrategy {
  /** disable polling outright */
  static NONE: false = false;

  /** poll with the default interval forever */
  static FIXED: number = defaultInterval;
}
