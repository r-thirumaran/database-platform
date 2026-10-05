import { useState } from 'react';
import { Check, Copy } from 'lucide-react';
import { copyText } from '../lib/format';

export function CopyId({ value, label = 'id', short = true }: { value: string; label?: string; short?: boolean }) {
  const [done, setDone] = useState(false);
  const onCopy = async () => {
    if (await copyText(value)) {
      setDone(true);
      setTimeout(() => setDone(false), 1500);
    }
  };
  return (
    <span className="copy-id" title={value}>
      <code>{short && value.length > 26 ? `${value.slice(0, 12)}…${value.slice(-6)}` : value}</code>
      <button type="button" onClick={onCopy} aria-label={`Copy ${label} ${value}`}>
        {done ? <Check /> : <Copy />}
      </button>
    </span>
  );
}

export function CopyButton({ value, label = 'Copy', className = 'btn sm' }: { value: string; label?: string; className?: string }) {
  const [done, setDone] = useState(false);
  return (
    <button
      type="button"
      className={className}
      onClick={async () => {
        if (await copyText(value)) {
          setDone(true);
          setTimeout(() => setDone(false), 1500);
        }
      }}
    >
      {done ? <Check /> : <Copy />} {done ? 'Copied' : label}
    </button>
  );
}
