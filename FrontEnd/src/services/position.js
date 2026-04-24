import api from './apiClient';

/** Lấy danh sách tất cả Positions */
export async function getPositions() {
  const res = await api.get('/positions/all');
  return res.data;
}

/** Lấy danh sách sessions của một Position */
export async function getSessionsByPosition(positionId) {
  const res = await api.get(`/api/chatbot/sessions?size=100`);
  const allSessions = res?.data?.data?.content || [];
  return allSessions.filter(s => s.positionId === Number(positionId));
}
