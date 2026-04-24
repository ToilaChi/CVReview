import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './context/AuthContext';
import Login from './pages/Login';
import Positions from './pages/Positions';
import HrChatHistory from './pages/HrChatHistory';
import ChatWorkspace from './pages/ChatWorkspace';
import CandidateChatHistory from './pages/CandidateChatHistory';
import CvManagement from './pages/CvManagement';
import './styles/global.css';

/** Route guard — redirect về login nếu chưa xác thực */
function ProtectedRoute({ children, requiredRole }) {
  const { user } = useAuth();
  if (!user) return <Navigate to="/login" replace />;
  if (requiredRole && user.role !== requiredRole) {
    return <Navigate to="/login" replace />;
  }
  return children;
}

function AppRoutes() {
  const { user } = useAuth();

  return (
    <Routes>
      {/* Public */}
      <Route path="/login" element={<Login />} />

      {/* HR Routes */}
      <Route
        path="/hr/positions"
        element={
          <ProtectedRoute requiredRole="HR">
            <Positions />
          </ProtectedRoute>
        }
      />
      <Route
        path="/hr/positions/:positionId/sessions"
        element={
          <ProtectedRoute requiredRole="HR">
            <HrChatHistory />
          </ProtectedRoute>
        }
      />
      <Route
        path="/hr/cv-management"
        element={
          <ProtectedRoute requiredRole="HR">
            <CvManagement />
          </ProtectedRoute>
        }
      />

      {/* Candidate Routes */}
      <Route
        path="/candidate/sessions"
        element={
          <ProtectedRoute requiredRole="CANDIDATE">
            <CandidateChatHistory />
          </ProtectedRoute>
        }
      />
      <Route
        path="/candidate/cv"
        element={
          <ProtectedRoute requiredRole="CANDIDATE">
            <CvManagement />
          </ProtectedRoute>
        }
      />

      {/* Shared Chat Route */}
      <Route
        path="/chat/:sessionId"
        element={
          <ProtectedRoute>
            <ChatWorkspace />
          </ProtectedRoute>
        }
      />

      {/* Default redirect based on role */}
      <Route
        path="/"
        element={
          user
            ? user.role === 'HR'
              ? <Navigate to="/hr/positions" replace />
              : <Navigate to="/candidate/sessions" replace />
            : <Navigate to="/login" replace />
        }
      />

      {/* Catch-all */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </AuthProvider>
  );
}
