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

export interface AuthResponse {
  token: string;
  user: User;
}

export interface ApiError {
  message: string;
  status?: number;
}
