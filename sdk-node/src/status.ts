import type { EngineStatus } from "./types.js";

/** Wrapper-owned status fields (the host runtime view), merged with engine status. */
export interface WrapperStatus {
  /** Whether the wrapper has loaded the WASM module and holds a live engine. */
  ready: boolean;
  /** Whether recurring polling is active. */
  polling: boolean;
  /** Epoch ms of the last refresh attempt (fetch), or null if none. */
  lastRefreshAt: number | null;
  /** Outcome of the last refresh attempt, or null if none yet. */
  lastRefreshOk: boolean | null;
}

/** Public status: engine status plus wrapper-owned runtime fields. */
export type KLSwitchStatus = EngineStatus & WrapperStatus;

export function combineStatus(engine: EngineStatus, wrapper: WrapperStatus): KLSwitchStatus {
  return { ...engine, ...wrapper };
}
