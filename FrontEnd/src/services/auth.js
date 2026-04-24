import api from './apiClient';

/**
 * Đăng nhập và trả về { accessToken, refreshToken, role, userId, ... }
 * Endpoint: POST /api/auth/login
 */
export async function login({ phone, password }) {
  console.log('📡 Calling API với:', { phone, password });
  const res = await api.post('/auth/login', { phone, password });
  console.log('📥 API trả về:', res);
  return res.data;
}
