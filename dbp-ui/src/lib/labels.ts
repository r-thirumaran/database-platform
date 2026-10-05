import type { PolicyKind } from '../api/types';

export const POLICY_LABEL: Record<PolicyKind, string> = {
  CROSS_TEAM_DIRECT_ACCESS: 'Cross-team direct access',
  UNOWNED_TABLE: 'Unowned table',
  UNDECLARED_CONSUMER: 'Undeclared consumer',
  WRITE_BY_NON_PRODUCER: 'Write by non-producer',
  DIRECT_DB_ACCESS_BYPASSING_PLATFORM: 'Direct DB access bypassing platform',
};
