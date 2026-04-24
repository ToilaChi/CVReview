import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import Layout from '../components/Layout';
import { getPositions } from '../services/position';
import styles from './Positions.module.css';

function formatDate(dateStr) {
  if (!dateStr) return '—';
  return new Date(dateStr).toLocaleDateString('vi-VN', {
    day: '2-digit', month: '2-digit', year: 'numeric',
  });
}

export default function Positions() {
  const [positions, setPositions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [activeTab, setActiveTab] = useState('active');
  const navigate = useNavigate();

  useEffect(() => {
    getPositions()
      .then((res) => {
        const list = res?.data?.content || res?.content || res || [];
        setPositions(Array.isArray(list) ? list : []);
      })
      .catch(() => setError('Không thể tải danh sách vị trí'))
      .finally(() => setLoading(false));
  }, []);

  const filtered = positions.filter((p) =>
    activeTab === 'active' ? p.isActive : !p.isActive,
  );

  return (
    <Layout>
      <div className={styles.page}>
        {/* Header */}
        <div className={styles.header}>
          <div>
            <h1 className="page-title">Positions</h1>
            <p className="page-subtitle">Quản lý các vị trí tuyển dụng</p>
          </div>
          <div className="tabs" style={{ width: 200 }}>
            <button
              id="tab-active"
              className={`tab ${activeTab === 'active' ? 'active' : ''}`}
              onClick={() => setActiveTab('active')}
            >
              ✅ Active
            </button>
            <button
              id="tab-inactive"
              className={`tab ${activeTab === 'inactive' ? 'active' : ''}`}
              onClick={() => setActiveTab('inactive')}
            >
              🗃 Inactive
            </button>
          </div>
        </div>

        {/* Content */}
        {loading && (
          <div className={styles.loadingWrap}>
            <div className="spinner" style={{ width: 28, height: 28 }} />
            <span style={{ color: 'var(--text-secondary)' }}>Đang tải...</span>
          </div>
        )}

        {error && <div className="alert alert-error">{error}</div>}

        {!loading && !error && filtered.length === 0 && (
          <div className="empty-state">
            <span className="empty-state-icon">📭</span>
            <p className="empty-state-title">Không có vị trí nào</p>
            <p className="empty-state-sub">
              {activeTab === 'active' ? 'Chưa có vị trí đang tuyển dụng' : 'Không có vị trí đã đóng'}
            </p>
          </div>
        )}

        <div className={styles.grid}>
          {filtered.map((pos) => (
            <div
              key={pos.id}
              id={`position-card-${pos.id}`}
              className={`card card-clickable ${styles.positionCard}`}
              onClick={() => navigate(`/hr/positions/${pos.id}/sessions`)}
            >
              <div className={styles.cardTop}>
                <div className={styles.posIcon}>💼</div>
                <span className={`badge ${pos.isActive ? 'badge-active' : 'badge-inactive'}`}>
                  {pos.isActive ? 'Active' : 'Inactive'}
                </span>
              </div>
              <h2 className={styles.posName}>{pos.name}</h2>
              <div className={styles.posMeta}>
                {pos.level && <span className={styles.metaTag}>{pos.level}</span>}
                {pos.language && <span className={styles.metaTag}>{pos.language}</span>}
              </div>
              <div className={styles.cardFooter}>
                <span className={styles.metaItem}>
                  📅 {formatDate(pos.openedAt ?? pos.createdAt)}
                </span>
                <span className={styles.metaItem}>
                  📄 {pos.cvCount ?? 0} CV
                </span>
              </div>
            </div>
          ))}
        </div>
      </div>
    </Layout>
  );
}
