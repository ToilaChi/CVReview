import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import Layout from '../components/Layout';
import { getMySessions, createCandidateSession } from '../services/chatbot';
import styles from './CandidateChatHistory.module.css';

function formatDate(dateStr) {
  if (!dateStr) return '';
  return new Date(dateStr).toLocaleDateString('vi-VN', {
    day: '2-digit', month: '2-digit', year: 'numeric',
  });
}

function formatTime(dateStr) {
  if (!dateStr) return '';
  return new Date(dateStr).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
}

export default function CandidateChatHistory() {
  const navigate = useNavigate();
  const [sessions, setSessions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    getMySessions()
      .then((data) => {
        const list = Array.isArray(data) ? data : [];
        setSessions(list.filter((s) => s.chatbotType === 'CANDIDATE'));
      })
      .catch(() => setError('Không thể tải lịch sử chat'))
      .finally(() => setLoading(false));
  }, []);

  async function handleNewChat() {
    setCreating(true);
    try {
      const session = await createCandidateSession();
      navigate(`/chat/${session.session_id}`, {
        state: { role: 'CANDIDATE' },
      });
    } catch {
      setError('Không thể tạo phiên chat mới');
    } finally {
      setCreating(false);
    }
  }

  return (
    <Layout>
      <div className={styles.page}>
        <div className={styles.header}>
          <div>
            <h1 className="page-title">Chat với AI</h1>
            <p className="page-subtitle">Tìm việc và phân tích CV phù hợp</p>
          </div>
          <button
            id="btn-new-chat-candidate"
            className="btn btn-primary"
            onClick={handleNewChat}
            disabled={creating}
          >
            {creating ? <span className="spinner" /> : '✦ Đoạn chat mới'}
          </button>
        </div>

        {error && <div className="alert alert-error">{error}</div>}

        {loading && (
          <div style={{ display: 'flex', justifyContent: 'center', padding: 40 }}>
            <div className="spinner" style={{ width: 28, height: 28 }} />
          </div>
        )}

        {!loading && sessions.length === 0 && !error && (
          <div className="empty-state">
            <span className="empty-state-icon">🤖</span>
            <p className="empty-state-title">Chưa có cuộc trò chuyện nào</p>
            <p className="empty-state-sub">Hãy tạo đoạn chat mới để bắt đầu tìm việc!</p>
          </div>
        )}

        <div className={styles.list}>
          {sessions.map((session) => (
            <div
              key={session.sessionId}
              id={`candidate-session-${session.sessionId}`}
              className={`card card-clickable ${styles.sessionCard}`}
              onClick={() =>
                navigate(`/chat/${session.sessionId}`, { state: { role: 'CANDIDATE' } })
              }
            >
              <div className={styles.sessionIcon}>💬</div>
              <div className={styles.sessionInfo}>
                <p className={styles.sessionDate}>
                  {formatDate(session.createdAt)}
                </p>
                <p className={styles.sessionTime}>
                  {session.lastActiveAt
                    ? `Hoạt động: ${formatDate(session.lastActiveAt)} lúc ${formatTime(session.lastActiveAt)}`
                    : `Tạo lúc: ${formatTime(session.createdAt)}`}
                </p>
              </div>
              <span className={styles.chevron}>›</span>
            </div>
          ))}
        </div>
      </div>
    </Layout>
  );
}
