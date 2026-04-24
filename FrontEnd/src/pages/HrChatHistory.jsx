import { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import Layout from '../components/Layout';
import { getSessionsByPosition } from '../services/position';
import { createHrSession } from '../services/chatbot';
import styles from './HrChatHistory.module.css';

function formatSessionLabel(session) {
  const date = new Date(session.createdAt);
  const monthYear = date.toLocaleDateString('vi-VN', { month: '2-digit', year: 'numeric' });
  const level = session.positionLevel ?? '';
  const lang = session.positionLanguage ?? '';
  const name = session.positionName ?? 'Position';
  return `${name}${level ? ` (${level}` : ''}${lang ? ` + ${lang}` : ''}${level ? ')' : ''} — ${monthYear}`;
}

function formatTime(dateStr) {
  if (!dateStr) return '';
  return new Date(dateStr).toLocaleString('vi-VN', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  });
}

export default function HrChatHistory() {
  const { positionId } = useParams();
  const navigate = useNavigate();
  const [sessions, setSessions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState('');

  const [showModeSelect, setShowModeSelect] = useState(false);

  useEffect(() => {
    getSessionsByPosition(positionId)
      .then(setSessions)
      .catch(() => setError('Không thể tải lịch sử chat'))
      .finally(() => setLoading(false));
  }, [positionId]);

  async function handleNewChat(mode) {
    setCreating(true);
    setShowModeSelect(false);
    try {
      const session = await createHrSession(Number(positionId), mode);
      navigate(`/chat/${session.session_id}`, {
        state: { role: 'HR', positionId: Number(positionId), mode: mode },
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
            <button className={`btn btn-ghost btn-sm ${styles.backBtn}`} onClick={() => navigate(-1)}>
              ← Quay lại
            </button>
            <h1 className="page-title" style={{ marginTop: 8 }}>Lịch sử Chat</h1>
            <p className="page-subtitle">Position #{positionId}</p>
          </div>
          <button
            id="btn-new-chat-hr"
            className="btn btn-primary"
            onClick={() => setShowModeSelect(true)}
            disabled={creating}
          >
            {creating ? <span className="spinner" /> : '✦ Tạo đoạn chat mới'}
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
            <span className="empty-state-icon">💬</span>
            <p className="empty-state-title">Chưa có phiên chat nào</p>
            <p className="empty-state-sub">Nhấn "Tạo đoạn chat mới" để bắt đầu</p>
          </div>
        )}

        <div className={styles.list}>
          {sessions.map((session) => (
            <div
              key={session.sessionId}
              id={`session-${session.sessionId}`}
              className={`card card-clickable ${styles.sessionCard}`}
              onClick={() =>
                navigate(`/chat/${session.sessionId}`, {
                  state: {
                    role: 'HR',
                    positionId: Number(positionId),
                    mode: session.mode ?? 'HR_MODE',
                  },
                })
              }
            >
              <div className={styles.sessionIcon}>💬</div>
              <div className={styles.sessionInfo}>
                <p className={styles.sessionLabel}>{formatSessionLabel(session)}</p>
                <p className={styles.sessionTime}>
                  {session.lastActiveAt
                    ? `Hoạt động: ${formatTime(session.lastActiveAt)}`
                    : `Tạo lúc: ${formatTime(session.createdAt)}`}
                </p>
              </div>
              <span className={`badge ${session.mode === 'CANDIDATE_MODE' ? 'badge-inactive' : 'badge-active'}`}>
                {session.mode === 'CANDIDATE_MODE' ? 'Candidate Mode' : 'HR Mode'}
              </span>
              <span className={styles.chevron}>›</span>
            </div>
          ))}
        </div>
      </div>

      {showModeSelect && (
        <div className="modalBackdrop">
          <div className="modal" style={{ maxWidth: 400 }}>
            <div className="modalHeader">
              <h2 className="modalTitle">Chọn chế độ Chat</h2>
              <button className="btn btn-ghost btn-sm" onClick={() => setShowModeSelect(false)}>✕</button>
            </div>
            <div className="modalBody" style={{ gap: 16 }}>
              <button 
                className="btn btn-primary" 
                style={{ width: '100%', justifyContent: 'center', padding: 16 }}
                onClick={() => handleNewChat('HR_MODE')}
              >
                👔 HR Mode
              </button>
              <p style={{ fontSize: 13, color: 'var(--text-muted)', marginTop: -12, textAlign: 'center' }}>
                Hỗ trợ sàng lọc, lọc CV và gửi email phỏng vấn.
              </p>
              <button 
                className="btn" 
                style={{ width: '100%', justifyContent: 'center', padding: 16, background: 'var(--bg-muted)' }}
                onClick={() => handleNewChat('CANDIDATE_MODE')}
              >
                👤 Candidate Mode
              </button>
              <p style={{ fontSize: 13, color: 'var(--text-muted)', marginTop: -12, textAlign: 'center' }}>
                Đóng vai candidate để đánh giá vị trí, xem feedback.
              </p>
            </div>
          </div>
        </div>
      )}
    </Layout>
  );
}
