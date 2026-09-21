import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuthStore } from './store/authStore';
import Login from './pages/Login';
import Home from './pages/Home';
import Planner from './pages/Planner';
import Trip from './pages/Trip';
import Profile from './pages/Profile';
import Expenses from './pages/Expenses';
import Memos from './pages/Memos';
import Office from './pages/Office';
import Learning from './pages/Learning';
import Health from './pages/Health';
import Schedule from './pages/Schedule';
import Assistant from './pages/Assistant';
import Travel from './pages/Travel';

function Protected({ children }: { children: ReactNode }) {
  const token = useAuthStore((s) => s.token);
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/" element={<Protected><Home /></Protected>} />
        <Route path="/planner" element={<Protected><Planner /></Protected>} />
        <Route path="/trip/:id" element={<Protected><Trip /></Protected>} />
        <Route path="/expenses" element={<Protected><Expenses /></Protected>} />
        <Route path="/memos" element={<Protected><Memos /></Protected>} />
        <Route path="/office" element={<Protected><Office /></Protected>} />
        <Route path="/learning" element={<Protected><Learning /></Protected>} />
        <Route path="/health" element={<Protected><Health /></Protected>} />
        <Route path="/schedule" element={<Protected><Schedule /></Protected>} />
        <Route path="/assistant" element={<Protected><Assistant /></Protected>} />
        <Route path="/travel" element={<Protected><Travel /></Protected>} />
        <Route path="/profile" element={<Protected><Profile /></Protected>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
