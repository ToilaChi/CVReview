import { useState, useEffect } from 'react';
import Layout from '../components/Layout';
import { getMyCVs, deleteCV, getCVDetail } from '../services/cv';
import styles from './CvManagement.module.css';

function formatDate(dateStr) {
  if (!dateStr) return '—';
  return new Date(dateStr).toLocaleDateString('vi-VN', {
    day: '2-digit', month: '2-digit', year: 'numeric',
  });
}

function scoreColor(score) {
  if (score == null) return 'var(--text-muted)';
  if (score >= 75) return 'var(--color-success)';
  if (score >= 50) return 'var(--color-warning)';
  return 'var(--color-danger)';
}

export default function CvManagement() {
  const [cvs, setCvs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [detailModal, setDetailModal] = useState(null);
  const [deleting, setDeleting] = useState(null);

  useEffect(() => {
    getMyCVs()
      .then((data) => setCvs(Array.isArray(data) ? data : []))
      .catch(() => setError('Không thể tải danh sách CV'))
      .finally(() => setLoading(false));
  }, []);

  async function handleView(cvId) {
    try {
      const detail = await getCVDetail(cvId);
      setDetailModal(detail);
    } catch {
      alert('Không thể tải chi tiết CV');
    }
  }

  async function handleDelete(cvId) {
    if (!window.confirm('Bạn có chắc muốn xóa CV này không?')) return;
    setDeleting(cvId);
    try {
      await deleteCV(cvId);
      setCvs((prev) => prev.filter((cv) => cv.id !== cvId));
    } catch {
      alert('Xóa CV thất bại');
    } finally {
      setDeleting(null);
    }
  }

  return (
    <Layout>
      <div className={styles.page}>
        <div className={styles.header}>
          <div>
            <h1 className="page-title">CV Management</h1>
            <p className="page-subtitle">Quản lý hồ sơ ứng viên</p>
          </div>
        </div>

        {error && <div className="alert alert-error">{error}</div>}

        {loading && (
          <div style={{ display: 'flex', justifyContent: 'center', padding: 48 }}>
            <div className="spinner" style={{ width: 28, height: 28 }} />
          </div>
        )}

        {!loading && cvs.length === 0 && !error && (
          <div className="empty-state">
            <span className="empty-state-icon">📄</span>
            <p className="empty-state-title">Chưa có CV nào</p>
            <p className="empty-state-sub">Hệ thống chưa có CV nào được upload</p>
          </div>
        )}

        <div className={styles.grid}>
          {cvs.map((cv) => (
            <div key={cv.id} id={`cv-card-${cv.id}`} className={`card ${styles.cvCard}`}>
              <div className={styles.cardTop}>
                <div className={styles.cvIcon}>📄</div>
                {cv.analysis?.score != null && (
                  <div className={styles.score} style={{ color: scoreColor(cv.analysis.score) }}>
                    {cv.analysis.score}<span className={styles.scoreUnit}>/100</span>
                  </div>
                )}
              </div>

              <h2 className={styles.cvName}>{cv.candidateName ?? cv.fileName ?? `CV #${cv.id}`}</h2>

              {cv.positionName && (
                <p className={styles.cvPosition}>📋 {cv.positionName}</p>
              )}

              <div className={styles.cvMeta}>
                <span className={styles.metaItem}>📅 {formatDate(cv.createdAt)}</span>
                {cv.sourceType && (
                  <span className={`badge ${cv.sourceType === 'HR' ? 'badge-inactive' : 'badge-active'}`}>
                    {cv.sourceType}
                  </span>
                )}
              </div>

              {cv.analysis?.skillMatch && (
                <p className={styles.skillMatch}>✅ {cv.analysis.skillMatch}</p>
              )}

              <div className={styles.actions}>
                <button
                  id={`btn-view-cv-${cv.id}`}
                  className="btn btn-ghost btn-sm"
                  onClick={() => handleView(cv.id)}
                >
                  👁 View
                </button>
                <button
                  id={`btn-delete-cv-${cv.id}`}
                  className="btn btn-danger btn-sm"
                  onClick={() => handleDelete(cv.id)}
                  disabled={deleting === cv.id}
                >
                  {deleting === cv.id ? <span className="spinner" style={{ width: 14, height: 14 }} /> : '🗑 Delete'}
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Detail Modal */}
      {detailModal && (
        <div className={styles.modalBackdrop} onClick={() => setDetailModal(null)}>
          <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
            <div className={styles.modalHeader}>
              <h2 className={styles.modalTitle}>Chi tiết CV</h2>
              <button
                id="btn-close-modal"
                className="btn-icon"
                onClick={() => setDetailModal(null)}
              >
                ✕
              </button>
            </div>
            <div className={styles.modalBody}>
              <div className={styles.detailRow}>
                <span className={styles.detailLabel}>Tên ứng viên</span>
                <span>{detailModal.candidateName ?? '—'}</span>
              </div>
              <div className={styles.detailRow}>
                <span className={styles.detailLabel}>Email</span>
                <span>{detailModal.candidateEmail ?? '—'}</span>
              </div>
              <div className={styles.detailRow}>
                <span className={styles.detailLabel}>Vị trí</span>
                <span>{detailModal.positionName ?? '—'}</span>
              </div>
              <div className={styles.detailRow}>
                <span className={styles.detailLabel}>Điểm match</span>
                <span style={{ color: scoreColor(detailModal.analysis?.score), fontWeight: 600 }}>
                  {detailModal.analysis?.score ?? '—'}
                </span>
              </div>
              {detailModal.analysis?.feedback && (
                <div className={styles.feedbackBox}>
                  <p className={styles.detailLabel}>Feedback</p>
                  <p className={styles.feedbackText}>{detailModal.analysis.feedback}</p>
                </div>
              )}
              {detailModal.analysis?.skillMiss && (
                <div className={styles.feedbackBox}>
                  <p className={styles.detailLabel}>Kỹ năng còn thiếu</p>
                  <p className={styles.feedbackText}>{detailModal.analysis.skillMiss}</p>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </Layout>
  );
}
