export type ActionRunner = (action: () => Promise<void>) => Promise<void>;
