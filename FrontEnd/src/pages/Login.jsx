import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { login as loginApi } from '../services/auth';
import styles from './Login.module.css';

export default function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState({ phone: '', password: '' });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e) {
    e.preventDefault();
    setError('');
    setLoading(true);

    console.log('📤 Form gửi đi:', form);

    try {
      const response = await loginApi(form);
      console.log('✅ Response:', response);
      const { accessToken, refreshToken, account } = response.data;

      login(
        { role: account.role, userId: account.id, phone: account.phone, name: account.name },
        accessToken,
        refreshToken,
      );

      if (account.role === 'HR') navigate('/hr/positions');
      else navigate('/candidate/sessions');
    } catch (err) {
      console.log('❌ Full error:', err); // 👈 log toàn bộ

      console.log('❌ err.response:', err.response);
      console.log('❌ err.response.data:', err.response?.data);
      console.log('❌ err.message:', err.message);

      const msg = err.response?.data?.message ?? 'Sai tài khoản hoặc mật khẩu';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className={styles.page}>
      <div className={styles.card}>
        <div className={styles.header}>
          <div className={styles.logo}>🎯</div>
          <h1 className={styles.title}>CVReview</h1>
          <p className={styles.subtitle}>Hệ thống tuyển dụng AI nội bộ</p>
        </div>

        <form onSubmit={handleSubmit} className={styles.form}>
          {error && <div className="alert alert-error">{error}</div>}

          <div className={styles.field}>
            <label className={styles.label} htmlFor="phone">Số điện thoại</label>
            <input
              id="phone"
              className="input"
              type="tel"
              placeholder="Nhập số điện thoại..."
              value={form.phone}
              onChange={(e) => setForm({ ...form, phone: e.target.value })}
              required
              autoComplete="tel"
            />
          </div>

          <div className={styles.field}>
            <label className={styles.label} htmlFor="password">Mật khẩu</label>
            <input
              id="password"
              className="input"
              type="password"
              placeholder="Nhập mật khẩu..."
              value={form.password}
              onChange={(e) => setForm({ ...form, password: e.target.value })}
              required
              autoComplete="current-password"
            />
          </div>

          <button
            id="btn-login"
            type="submit"
            className={`btn btn-primary ${styles.submitBtn}`}
            disabled={loading}
          >
            {loading ? <span className="spinner" /> : 'Đăng nhập'}
          </button>
        </form>

        <p className={styles.hint}>HR hoặc Candidate đều đăng nhập tại đây</p>
      </div>
    </div>
  );
}
