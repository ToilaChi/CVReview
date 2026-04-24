import api from './apiClient';

/** Lấy danh sách CV của Candidate hiện tại */
export async function getMyCVs() {
  const res = await api.get('/api/profiles/me');
  return res.data;
}

/** Xóa một CV theo ID */
export async function deleteCV(cvId) {
  const res = await api.delete(`/api/profiles/${cvId}`);
  return res.data;
}

/** Lấy chi tiết một CV */
export async function getCVDetail(cvId) {
  const res = await api.get(`/api/profiles/${cvId}`);
  return res.data;
}
