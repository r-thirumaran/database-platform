import { useState } from 'react';

/**
 * Local draft state that re-synchronises when the server value changes (React's "adjust state when a
 * prop changes" pattern: setState during render, no effect, no extra commit).
 */
export function useSyncedState<T>(source: T): [T, (v: T | ((prev: T) => T)) => void] {
  const [state, setState] = useState(source);
  const [prev, setPrev] = useState(source);
  if (prev !== source) {
    setPrev(source);
    setState(source);
  }
  return [state, setState];
}

/** State that resets to `initial` whenever `key` changes. */
export function useResettableState<T>(initial: T, key: string): [T, (v: T | ((prev: T) => T)) => void] {
  const [state, setState] = useState(initial);
  const [prevKey, setPrevKey] = useState(key);
  if (prevKey !== key) {
    setPrevKey(key);
    setState(initial);
  }
  return [state, setState];
}
