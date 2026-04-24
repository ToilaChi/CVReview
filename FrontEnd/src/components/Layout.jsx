import { NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import styles from './Layout.module.css';

const HR_NAV = [
  { to: '/hr/positions', icon: '📋', label: 'Positions' },
  { to: '/hr/cv-management', icon: '📄', label: 'CV Management' },
];

const CANDIDATE_NAV = [
  { to: '/candidate/sessions', icon: '💬', label: 'Chat với AI' },
  { to: '/candidate/cv', icon: '📄', label: 'CV của tôi' },
];

export default function Layout({ children }) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const isHR = user?.role === 'HR';
  const navItems = isHR ? HR_NAV : CANDIDATE_NAV;

  function handleLogout() {
    logout();
    navigate('/login');
  }

  return (
    <div className={styles.shell}>
      {/* Sidebar */}
      <aside className={styles.sidebar}>
        <div className={styles.brand}>
          <span className={styles.brandIcon}>🎯</span>
          <span className={styles.brandName}>CVReview</span>
        </div>

        <nav className={styles.nav}>
          {navItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                `${styles.navItem} ${isActive ? styles.navItemActive : ''}`
              }
            >
              <span className={styles.navIcon}>{item.icon}</span>
              <span>{item.label}</span>
            </NavLink>
          ))}
        </nav>

        <div className={styles.sidebarFooter}>
          <div className={styles.userInfo}>
            <div className={styles.userAvatar}>
              {(user?.name || user?.phone)?.[0]?.toUpperCase() ?? '?'}
            </div>
            <div className={styles.userMeta}>
              <span className={styles.userName}>{user?.name || user?.phone || 'User'}</span>
              <span className={styles.userRole}>{user?.role ?? ''}</span>
            </div>
          </div>
          <button className={styles.logoutBtn} onClick={handleLogout} title="Đăng xuất">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
              <polyline points="16 17 21 12 16 7" />
              <line x1="21" y1="12" x2="9" y2="12" />
            </svg>
          </button>
        </div>
      </aside>

      {/* Main content */}
      <main className={styles.main}>
        {children}
      </main>
    </div>
  );
}
