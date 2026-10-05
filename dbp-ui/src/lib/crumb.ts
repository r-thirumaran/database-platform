import { useSyncExternalStore } from 'react';

/** Tiny external store so detail pages can publish their breadcrumb label to the top bar. */
let current: string | undefined;
const listeners = new Set<() => void>();

export function setCrumb(label: string | undefined) {
  if (current === label) return;
  current = label;
  listeners.forEach((l) => l());
}
export function useCrumb(): string | undefined {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => current,
  );
}
