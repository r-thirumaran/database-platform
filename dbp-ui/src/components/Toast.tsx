import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

export interface Toast { id: number; kind: 'info' | 'success' | 'error'; message: string }
interface Ctx { toast: (message: string, kind?: Toast['kind']) => void; success: (m: string) => void; error: (m: string) => void }
const ToastCtx = createContext<Ctx>({ toast: () => {}, success: () => {}, error: () => {} });
let seq = 0;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<Toast[]>([]);
  const toast = useCallback((message: string, kind: Toast['kind'] = 'info') => {
    const id = ++seq;
    setItems((l) => [...l, { id, kind, message }]);
    setTimeout(() => setItems((l) => l.filter((t) => t.id !== id)), kind === 'error' ? 7000 : 4000);
  }, []);
  const value = useMemo<Ctx>(() => ({ toast, success: (m) => toast(m, 'success'), error: (m) => toast(m, 'error') }), [toast]);
  return (
    <ToastCtx.Provider value={value}>
      {children}
      <div className="toasts" aria-live="polite" aria-atomic="false">
        {items.map((t) => (
          <div key={t.id} className={`toast ${t.kind}`} role={t.kind === 'error' ? 'alert' : 'status'}>
            {t.message}
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

// eslint-disable-next-line react-refresh/only-export-components
export const useToast = () => useContext(ToastCtx);
