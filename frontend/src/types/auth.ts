export interface Identity {
  tenantId: string;
  tenantSlug: string;
  tenantName: string;
  userId: string;
  username: string;
  role: string;
}

export interface LoginForm {
  tenantSlug: string;
  tenantName: string;
  username: string;
  password: string;
}
