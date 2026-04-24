import api from './apiClient';

const CHATBOT_URL = 'http://localhost:8085';

// Helper: lấy userId từ localStorage
function getUserId() {
  const user = JSON.parse(localStorage.getItem('cvreview_user') || '{}');
  return user?.userId || '';
}

/** Lấy tất cả sessions của user hiện tại (Candidate hoặc HR) */
export async function getMySessions() {
  const res = await api.get('/api/chatbot/sessions?size=100');
  return res?.data?.data?.content || [];
}

/** Lấy full history của 1 session */
export async function getSessionHistory(sessionId) {
  const res = await api.get(`/api/chatbot/sessions/${sessionId}`);
  return res?.data?.data || [];
}

/** Khởi tạo HR session mới */
export async function createHrSession(positionId, mode) {
  const res = await api.post(`${CHATBOT_URL}/chatbot/hr/session`, { 
    hr_id: getUserId(), 
    position_id: positionId, 
    mode 
  });
  return res.data;
}

/** Gửi message HR chatbot */
export async function sendHrMessage(sessionId, message, mode, positionId) {
  const res = await api.post(`${CHATBOT_URL}/chatbot/hr/chat`, { 
    session_id: sessionId, 
    query: message, 
    hr_id: getUserId(),
    position_id: positionId,
    mode 
  });
  return res.data;
}

/** Khởi tạo Candidate session mới */
export async function createCandidateSession() {
  const res = await api.post(`${CHATBOT_URL}/chatbot/candidate/session`, { 
    user_id: getUserId() 
  });
  return res.data;
}

/** Gửi message Candidate chatbot */
export async function sendCandidateMessage(sessionId, message) {
  const res = await api.post(`${CHATBOT_URL}/chatbot/candidate/chat`, { 
    session_id: sessionId, 
    query: message,
    candidate_id: getUserId()
  });
  return res.data;
}
