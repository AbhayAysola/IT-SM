/**
 * Roles the frontend gates on. The backend currently issues only `USER` (see backend UserEntity), so `role` stays
 * a string: until the backend provisions these roles, no account matches `ADMIN`.
 */
export type Role = 'ADMIN' | 'TECHNICIAN' | 'EMPLOYEE';

export interface User {
  id: number;
  email: string;
  name: string;
  role: string;
  createdAt: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  name: string;
}

/** `POST /api/auth/register` answers 202 with exactly this for a new AND an already registered address; there is no token. */
export interface RegistrationResponse {
  status: 'ACCEPTED';
}

export interface AuthResponse {
  token: string;
  user: User;
}

export interface ApiError {
  message: string;
  status?: number;
}
