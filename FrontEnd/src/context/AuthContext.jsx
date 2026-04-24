import { createContext, useContext, useState, useCallback } from 'react';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => {
    try {
      const stored = localStorage.getItem('cvreview_user');
      return stored ? JSON.parse(stored) : null;
    } catch {
      return null;
    }
  });

  const login = useCallback((userData, accessToken, refreshToken) => {
    const userPayload = { ...userData, accessToken, refreshToken };
    localStorage.setItem('cvreview_user', JSON.stringify(userPayload));
    localStorage.setItem('cvreview_token', accessToken);
    setUser(userPayload);
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem('cvreview_user');
    localStorage.removeItem('cvreview_token');
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
